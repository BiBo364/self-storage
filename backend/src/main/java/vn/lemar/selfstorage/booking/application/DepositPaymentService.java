package vn.lemar.selfstorage.booking.application;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.exception.DepositNotAllowedException;
import vn.lemar.selfstorage.booking.application.exception.EmailNotVerifiedException;
import vn.lemar.selfstorage.booking.application.exception.InvoiceNotFoundException;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.facility.application.StorageUnitQuery;
import vn.lemar.selfstorage.facility.application.StorageUnitView;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.PaymentSessionQuery;
import vn.lemar.selfstorage.payment.application.dto.PaymentRequest;

/**
 * Đặt cọc giữ kho — spec `draft.md` §1.4.
 *
 * <p>Năm nhánh kiểm chạy đúng thứ tự spec và dừng ở nhánh đầu tiên không đạt. Thứ tự quan trọng:
 * đơn quá hạn phải chặn trước khi đụng tới khoang, còn khóa phiên phải kiểm sau cùng để không
 * báo "đang xử lý" cho một khoang thật ra đã `Reserved`.
 *
 * <p>Service này <strong>không</strong> tự ghi nhận đã thu tiền. Nó chỉ mở phiên; việc chốt
 * thuộc {@link DepositPaymentListener} khi cổng xác nhận qua IPN.
 */
@Service
public class DepositPaymentService {

    private static final DateTimeFormatter TXN_SUFFIX =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private static final String TXN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int TXN_SUFFIX_LENGTH = 5;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    // Nguyên văn spec §1.4 — FE hiển thị thẳng cho khách nên không được diễn đạt lại.
    private static final String ORDER_EXPIRED =
            "Đơn thuê kho đã hết hạn. Vui lòng liên hệ cơ sở để được hỗ trợ.";
    private static final String MAINTENANCE_STATUS = "Maintenance";

    private static final String UNIT_TAKEN =
            "Khoang chứa đã được đặt cọc bởi khách hàng khác. Hóa đơn này đã hết hiệu lực.";
    private static final String UNIT_MAINTENANCE =
            "Khoang chứa hiện không khả dụng. Vui lòng liên hệ cơ sở để được hỗ trợ.";
    private static final String UNIT_BUSY =
            "Khoang chứa này hiện đang trong quá trình xử lý thanh toán (đặt cọc / gia hạn) "
                    + "bởi một khách hàng khác. Vui lòng quay lại thử lại sau ít phút "
                    + "hoặc chọn khoang chứa khác!";
    private static final String PAYMENT_ALREADY_SUCCEEDED =
            "Hóa đơn này đã được thanh toán thành công. Vui lòng chờ hệ thống xác nhận.";
    private static final String SESSION_LOST =
            "Phiên thanh toán trước đã hết hiệu lực. Vui lòng thử lại sau ít phút.";

    private final InvoiceRepository invoices;
    private final RentalOrderRepository orders;
    private final StorageUnitQuery units;
    private final PaymentService payments;
    private final PaymentSessionQuery sessions;
    private final CurrentAccountProvider currentAccount;
    private final InvoiceVoidingService invoiceVoiding;

    public DepositPaymentService(InvoiceRepository invoices, RentalOrderRepository orders,
            StorageUnitQuery units, PaymentService payments, PaymentSessionQuery sessions,
            CurrentAccountProvider currentAccount, InvoiceVoidingService invoiceVoiding) {
        this.invoices = invoices;
        this.orders = orders;
        this.units = units;
        this.payments = payments;
        this.sessions = sessions;
        this.currentAccount = currentAccount;
        this.invoiceVoiding = invoiceVoiding;
    }

    /**
     * Mở một phiên thanh toán cho hóa đơn cọc và trả URL để FE chuyển hướng khách sang VNPay.
     *
     * @throws InvoiceNotFoundException   hóa đơn không tồn tại hoặc không thuộc người gọi
     * @throws EmailNotVerifiedException  khách chưa xác minh email
     * @throws DepositNotAllowedException rơi vào một trong các nhánh từ chối của spec §1.4
     */
    @Transactional(noRollbackFor = DepositNotAllowedException.class)
    public String startDeposit(Long invoiceId, String clientIp) {
        if (!currentAccount.isCurrentAccountVerified()) {
            throw new EmailNotVerifiedException();
        }
        Long actorId = currentAccount.getCurrentAccountId();

        // Lấy order_id bằng projection để khoá đơn TRƯỚC khi nạp entity hoá đơn — nạp trước là
        // quay lại đúng thứ tự khoá sai. Hoá đơn không tồn tại/không thuộc khách/không gắn đơn
        // (hoá đơn thuê) rơi vào nhánh dưới, đọc không khoá chỉ để trả đúng 404/409.
        Long orderId = invoices.findOrderIdByIdAndCustomerId(invoiceId, actorId).orElse(null);
        if (orderId == null) {
            Invoice snapshot = invoices.findById(invoiceId)
                    .filter(found -> actorId.equals(found.getCustomerId()))
                    .orElseThrow(InvoiceNotFoundException::new);
            if (snapshot.getStatus() != InvoiceStatus.UNPAID || !isDepositKind(snapshot.getType())) {
                throw new DepositNotAllowedException("Hóa đơn này không ở trạng thái chờ thanh toán cọc.");
            }
            throw new InvoiceNotFoundException();
        }

        // Khoá đơn TRƯỚC hoá đơn và khoang — cùng thứ tự với listener IPN và cascade, tránh deadlock.
        RentalOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(InvoiceNotFoundException::new);
        Invoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(InvoiceNotFoundException::new);
        if (!actorId.equals(invoice.getCustomerId())
                || invoice.getStatus() != InvoiceStatus.UNPAID
                || !isDepositKind(invoice.getType())) {
            throw new DepositNotAllowedException("Hóa đơn này không ở trạng thái chờ thanh toán cọc.");
        }

        // IPN đã ghi PAY/Success nhưng listener AFTER_COMMIT chưa chạy/lỗi: invoice vẫn Unpaid.
        // Không mở phiên mới và không huỷ hoá đơn — khách sẽ trả lần hai, còn giao dịch đầu không
        // có đường tự chốt. Đối soát/querydr xử lý phần còn lại.
        if (sessions.hasSuccessfulPayment(invoice.getId())) {
            throw new DepositNotAllowedException(PAYMENT_ALREADY_SUCCEEDED);
        }

        // (1) Đơn quá hạn — chặn trước khi đụng tới khoang.
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(Instant.now())) {
            throw new DepositNotAllowedException(ORDER_EXPIRED);
        }

        // Khách bấm back: cùng invoice đang có phiên Pending trong 15 phút → trả lại URL cũ,
        // không mở phiên mới. URL là cache Redis; mất cache thì chặn cho tới khi phiên hết hạn,
        // thay vì mở URL thứ hai và để khách trả hai lần.
        if (sessions.hasOpenSessionForInvoice(invoice.getId())) {
            return sessions.openPaymentUrl(invoice.getId())
                    .or(() -> payments.resumePayment(invoice.getId(), clientIp))
                    .orElseThrow(() -> new DepositNotAllowedException(SESSION_LOST));
        }

        // Khoá khoang sau đơn và hoá đơn — cùng thứ tự với listener IPN và cascade. Khoang thuộc
        // module facility nên đi qua public API của họ, không map entity thứ hai lên cùng bảng.
        StorageUnitView unit = units.lock(order.getUnitId())
                .orElseThrow(() -> new DepositNotAllowedException(UNIT_MAINTENANCE));

        // (2)(3) Khoang không còn dùng được — hóa đơn hết hiệu lực, FM phải đề xuất khoang khác.
        // Đơn đang giữ khoang (Deposited/Scheduled/InProgress) phải bật cờ, nếu không FM bị gate
        // repropose chặn dù notification bảo họ đề xuất lại.
        if (!unit.isAvailable()) {
            if (order.holdsUnit()) {
                order.requireReproposal();
            }
            voidInvoice(invoice, unit, actorId);
            throw new DepositNotAllowedException(
                    MAINTENANCE_STATUS.equals(unit.status()) ? UNIT_MAINTENANCE : UNIT_TAKEN);
        }

        // (4) Khoang đang có phiên thanh toán của NGƯỜI KHÁC còn mở. Kiểm theo khoang chứ không
        // theo hóa đơn, vì hai khách trả cho hai hóa đơn khác nhau vẫn tranh cùng một khoang —
        // nhưng phải trừ chính hóa đơn này ra: khách bấm back rồi bấm lại không phải "khách khác",
        // chặn họ 15 phút là sai.
        if (sessions.hasOpenSession(otherInvoicesOnUnit(unit.id(), invoice.getId()))) {
            throw new DepositNotAllowedException(UNIT_BUSY);
        }

        // Hai request cùng invoice có thể đều qua phép kiểm phía trên trước khi bên kia kịp tạo
        // Pending. Sau khi đã khoá khoang, kiểm lại để bên thua trả URL cũ thay vì mở Pending thứ hai.
        if (sessions.hasOpenSessionForInvoice(invoice.getId())) {
            return sessions.openPaymentUrl(invoice.getId())
                    .or(() -> payments.resumePayment(invoice.getId(), clientIp))
                    .orElseThrow(() -> new DepositNotAllowedException(SESSION_LOST));
        }

        // (5) Thông thoáng — mở phiên. Số tiền đọc từ hóa đơn trong DB, không tin tham số caller.
        PaymentRequest request = new PaymentRequest(
                nextTxnRef(invoice.getId()), invoice.getAmount(), invoice.getTitle(), clientIp);
        return payments.startPayment(invoice.getId(), request);
    }

    /**
     * Huỷ hoá đơn trong cùng transaction. Phần ghi này sống sót qua 409 nhờ
     * {@code noRollbackFor = DepositNotAllowedException.class} ở {@link #startDeposit}.
     */
    private void voidInvoice(Invoice invoice, StorageUnitView unit, Long actorId) {
        invoiceVoiding.voidForUnavailableUnit(
                invoice.getId(), unit.code(), unit.facilityId(), actorId);
    }

    /** Cọc đầu và cọc bổ sung đều mở phiên ở đây; khác nhau ở listener lúc chốt. */
    private static boolean isDepositKind(InvoiceType type) {
        return type == InvoiceType.DEPOSIT || type == InvoiceType.DEPOSIT_SUPPLEMENT;
    }

    /** Hóa đơn của mọi đơn trỏ vào khoang này, trừ hóa đơn đang được thanh toán. */
    private List<Long> otherInvoicesOnUnit(Long unitId, Long currentInvoiceId) {
        List<Long> orderIds = orders.findByUnitId(unitId).stream().map(RentalOrder::getId).toList();
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return invoices.findByOrderIdIn(orderIds).stream()
                .map(Invoice::getId)
                .filter(id -> !id.equals(currentInvoiceId))
                .toList();
    }

    /**
     * VNPay yêu cầu {@code vnp_TxnRef} không trùng trong ngày và chỉ gồm chữ/số.
     *
     * <p>Mốc thời gian chỉ tới giây, nên hai lần bấm trong cùng một giây sẽ ra mã y hệt và vỡ
     * unique constraint — khách nhận 500 thay vì lỗi nghiệp vụ. Thêm hậu tố ngẫu nhiên để mã
     * duy nhất kể cả khi trùng giây.
     */
    private String nextTxnRef(Long invoiceId) {
        return "INV" + invoiceId + "T" + TXN_SUFFIX.format(Instant.now()) + randomSuffix();
    }

    private String randomSuffix() {
        StringBuilder suffix = new StringBuilder(TXN_SUFFIX_LENGTH);
        for (int i = 0; i < TXN_SUFFIX_LENGTH; i++) {
            suffix.append(TXN_ALPHABET.charAt(RANDOM.nextInt(TXN_ALPHABET.length())));
        }
        return suffix.toString();
    }
}
