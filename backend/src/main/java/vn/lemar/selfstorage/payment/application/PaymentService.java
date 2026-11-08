package vn.lemar.selfstorage.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.payment.application.dto.GatewayNotification;
import vn.lemar.selfstorage.payment.application.dto.GatewayQueryResult;
import vn.lemar.selfstorage.payment.application.dto.IpnResponse;
import vn.lemar.selfstorage.payment.application.dto.PaymentRequest;
import vn.lemar.selfstorage.payment.application.event.PaymentFailed;
import vn.lemar.selfstorage.payment.application.event.PaymentSucceeded;
import vn.lemar.selfstorage.payment.config.VnPayProperties;
import vn.lemar.selfstorage.payment.domain.PaymentStatus;
import vn.lemar.selfstorage.payment.domain.PaymentTransaction;
import vn.lemar.selfstorage.payment.repository.PaymentTransactionRepository;

/**
 * Điểm vào duy nhất để thu tiền. Flow 1 thu cọc, Flow 2 thu tháng đầu, Flow 3 thu gia hạn đều
 * gọi vào đây thay vì tự viết phần VNPay.
 */
@Service
public class PaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

    /** VNPay gửi số tiền đã nhân 100 nên nhận về phải chia lại. */
    private static final BigDecimal AMOUNT_SCALE = BigDecimal.valueOf(100);

    private static final String SUCCESS_CODE = "00";
    private static final ZoneId VNPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** Cửa sổ phiên VNPay (15') + đệm 5' — quá mốc này cổng chắc chắn không nhận thanh toán nữa. */
    static final java.time.Duration SESSION_EXPIRY = java.time.Duration.ofMinutes(20);

    /**
     * Trần coi một giao dịch {@code Pending} là "phiên mở": cổng đã đóng link sau {@link #PAYMENT_WINDOW}, nên quá
     * mốc này mà vẫn chưa xác nhận được (cổng lỗi kéo dài, sai cấu hình) thì không chặn hết hạn hoá đơn / huỷ đơn
     * nữa. Giao dịch vẫn {@code Pending} để đối soát tiếp; tiền về muộn đi nhánh "xác nhận muộn" (báo FM hoàn).
     */
    static final java.time.Duration OPEN_SESSION_LIMIT = java.time.Duration.ofHours(24);

    /** Cửa sổ phiên VNPay — khớp {@code vnp_ExpireDate} của {@link VnPayGateway}. */
    public static final java.time.Duration PAYMENT_WINDOW = java.time.Duration.ofMinutes(15);

    private final PaymentGateway gateway;
    private final PaymentTransactionRepository repository;
    private final VnPayProperties properties;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentGateway gateway, PaymentTransactionRepository repository,
                          VnPayProperties properties, ApplicationEventPublisher events,
                          ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.repository = repository;
        this.properties = properties;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * Ghi nhận một lượt thanh toán ở trạng thái {@code Pending} rồi trả URL để redirect khách.
     *
     * <p>Phải tạo bản ghi <em>trước</em> khi chuyển hướng: nếu khách đóng trình duyệt giữa chừng,
     * giao dịch vẫn còn dấu vết để đối soát.
     */
    @Transactional
    public String startPayment(Long invoiceId, PaymentRequest request) {
        Objects.requireNonNull(invoiceId, "invoiceId bắt buộc");
        PaymentTransaction transaction = new PaymentTransaction(
                invoiceId, request.txnRef(), request.amount(), request.orderInfo());
        repository.save(transaction);

        // vnp_CreateDate = created_at của giao dịch: querydr cần đúng mốc này để tìm lại phiên.
        return gateway.buildPaymentUrl(request.withCreatedAt(transaction.getCreatedAt()));
    }

    /**
     * Thanh toán một hoá đơn bằng cổng đang dùng: cổng sinh mã giao dịch (payOS cần mã số) rồi tạo phiên như
     * {@link #startPayment}.
     */
    @Transactional
    public String startInvoicePayment(Long invoiceId, BigDecimal amount, String orderInfo, String ipAddr) {
        return startPayment(invoiceId, new PaymentRequest(gateway.newTxnRef(invoiceId), amount, orderInfo, ipAddr));
    }

    /** Kết quả xử lý thông báo thanh toán dạng body (webhook). */
    public enum NotificationResult { ACCEPTED, ALREADY_SETTLED, NOT_FOUND, INVALID_SIGNATURE, INVALID_AMOUNT }

    /**
     * Webhook của cổng (payOS): kiểm chữ ký → tìm giao dịch (khoá row) → số tiền → còn {@code Pending} → ghi
     * {@code Success}/{@code Failed} + event. Idempotent theo mã giao dịch như IPN VNPay.
     */
    @Transactional
    public NotificationResult handleNotification(String rawBody) {
        GatewayNotification notification = gateway.parseNotification(rawBody).orElse(null);
        if (notification == null) {
            LOG.warn("Webhook bị từ chối vì sai chữ ký / không đọc được");
            return NotificationResult.INVALID_SIGNATURE;
        }
        PaymentTransaction transaction = repository.findByVnpTxnRef(notification.txnRef()).orElse(null);
        if (transaction == null) {
            return NotificationResult.NOT_FOUND;
        }
        if (notification.amount() == null || notification.amount().compareTo(transaction.getAmount()) != 0) {
            LOG.warn("Webhook {} số tiền {} lệch giao dịch {}", notification.txnRef(), notification.amount(),
                    transaction.getAmount());
            return NotificationResult.INVALID_AMOUNT;
        }
        if (!transaction.isPending()) {
            if (notification.success() && transaction.getStatus() == PaymentStatus.FAILED) {
                // Phiên đã bị huỷ/đóng phía mình nhưng khách vẫn chuyển khoản (vd quét QR cũ) — tiền đã vào.
                settleLate(transaction, notification.transactionNo(), notification.paidAt(), notification.payload());
            }
            return NotificationResult.ALREADY_SETTLED;
        }
        if (notification.success()) {
            transaction.markSucceeded(notification.transactionNo(),
                    notification.paidAt() == null ? Instant.now() : notification.paidAt(), notification.payload());
            events.publishEvent(new PaymentSucceeded(transaction.getInvoiceId(), transaction.getVnpTxnRef(),
                    transaction.getAmount(), transaction.getPaidAt(), transaction.getId()));
        } else {
            String reason = "Cổng báo thanh toán không thành công";
            transaction.markFailed(reason, notification.payload());
            events.publishEvent(new PaymentFailed(transaction.getInvoiceId(), transaction.getVnpTxnRef(),
                    transaction.getAmount(), reason, transaction.getId()));
        }
        return NotificationResult.ACCEPTED;
    }

    /**
     * Tiền vào trên giao dịch đã đóng: ghi {@code SUCCESS} + phát {@link PaymentSucceeded} — booking chốt hoá đơn nếu
     * còn chờ, không thì báo FM hoàn (nhánh xác nhận muộn). Số này tính vào {@link #refundable} nên FM ghi nhận hoàn
     * được. Gọi lặp không ghi lại: giao dịch đã {@code SUCCESS} thì caller không vào đây.
     */
    private void settleLate(PaymentTransaction transaction, String transactionNo, Instant paidAt, String payload) {
        LOG.error("Thanh toán muộn: giao dịch {} đã đóng ({}) nhưng cổng báo đã thu {} — chốt / hoàn thủ công",
                transaction.getVnpTxnRef(), transaction.getFailureReason(), transaction.getAmount());
        transaction.markLateSucceeded(transactionNo, paidAt == null ? Instant.now() : paidAt, payload);
        events.publishEvent(new PaymentSucceeded(transaction.getInvoiceId(), transaction.getVnpTxnRef(),
                transaction.getAmount(), transaction.getPaidAt(), transaction.getId()));
    }

    /** Ảnh chụp trạng thái giao dịch cho caller (không lộ entity ra ngoài module). */
    public record PaymentSnapshot(Long invoiceId, String txnRef, String status, String failureReason,
                                  Instant createdAt) {
    }

    /** Giao dịch mới nhất của hoá đơn, không gọi cổng. */
    @Transactional(readOnly = true)
    public Optional<PaymentSnapshot> latest(Long invoiceId) {
        return repository.findFirstByInvoiceIdOrderByCreatedAtDescIdDesc(invoiceId).map(PaymentService::snapshot);
    }

    /**
     * Đối soát giao dịch mới nhất của hoá đơn bằng {@code querydr} — dùng khi khách quay về từ cổng mà IPN chưa
     * tới (vd chạy local không có URL public). Giao dịch đã chốt thì trả nguyên trạng, không gọi cổng.
     */
    @Transactional
    public Optional<PaymentSnapshot> reconcileLatest(Long invoiceId, String ipAddr) {
        return repository.findFirstByInvoiceIdOrderByCreatedAtDescIdDesc(invoiceId)
                .map(t -> reconcile(t.getVnpTxnRef(), ipAddr));
    }

    /**
     * Đối soát một giao dịch {@code Pending} với cổng ({@code querydr}) — nguồn dự phòng duy nhất ngoài IPN.
     * Cổng xác nhận đã trả → {@code Success} + {@link PaymentSucceeded}; cổng báo lỗi → {@code Failed} +
     * {@link PaymentFailed}; cổng không có giao dịch và đã quá cửa sổ phiên → {@code Failed} (phiên hết hạn).
     * {@code querydr} lỗi mạng / sai chữ ký → giữ {@code Pending}, lần sau đối soát tiếp.
     */
    @Transactional
    public PaymentSnapshot reconcile(String txnRef, String ipAddr) {
        PaymentTransaction transaction = repository.findByVnpTxnRef(txnRef)
                .orElseThrow(() -> new IllegalArgumentException("Không có giao dịch " + txnRef));
        if (!transaction.isPending()) {
            return snapshot(transaction);
        }
        GatewayQueryResult result;
        try {
            result = gateway.query(txnRef, transaction.getCreatedAt(), transaction.getTransactionContent(), ipAddr);
        } catch (RuntimeException e) {
            LOG.warn("querydr {} lỗi, giữ Pending: {}", txnRef, e.getMessage());
            return snapshot(transaction);
        }
        if (result == null) {
            return snapshot(transaction);
        }
        String payload = toJson(result.payload());
        if (result.paid()) {
            if (result.amount() == null || result.amount().compareTo(transaction.getAmount()) != 0) {
                LOG.error("querydr {}: số tiền cổng ({}) lệch giao dịch ({}) — giữ Pending để xử lý thủ công",
                        txnRef, result.amount(), transaction.getAmount());
                return snapshot(transaction);
            }
            transaction.markSucceeded(result.transactionNo(), parsePayDate(result.payDate()), payload);
            events.publishEvent(new PaymentSucceeded(transaction.getInvoiceId(), txnRef,
                    transaction.getAmount(), transaction.getPaidAt(), transaction.getId()));
        } else if (result.failed()) {
            String reason = "querydr: vnp_TransactionStatus=" + result.transactionStatus();
            transaction.markFailed(reason, payload);
            events.publishEvent(new PaymentFailed(transaction.getInvoiceId(), txnRef, transaction.getAmount(), reason,
                    transaction.getId()));
        } else if (result.notFound() && Instant.now().isAfter(transaction.getCreatedAt().plus(SESSION_EXPIRY))) {
            String reason = "Phiên thanh toán hết hạn, cổng không ghi nhận giao dịch";
            transaction.markFailed(reason, payload);
            events.publishEvent(new PaymentFailed(transaction.getInvoiceId(), txnRef, transaction.getAmount(), reason,
                    transaction.getId()));
        }
        return snapshot(transaction);
    }

    /**
     * Hoá đơn nào trong danh sách đang có phiên thanh toán mở: còn giao dịch {@code Pending} — cho tới khi cổng
     * xác nhận phiên đã đóng (đã trả / huỷ / hết hạn, qua webhook hoặc đối soát). Nhờ vậy không thể mở phiên thứ
     * hai cho cùng khoang trong lúc phiên đầu còn trả được tiền. Giao dịch {@code Pending} quá
     * {@link #OPEN_SESSION_LIMIT} không còn tính là phiên mở.
     */
    @Transactional(readOnly = true)
    public List<Long> invoicesWithOpenSession(Collection<Long> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return List.of();
        }
        return repository.findInvoiceIdsWithStatusSince(invoiceIds, PaymentStatus.PENDING,
                Instant.now().minus(OPEN_SESSION_LIMIT));
    }

    /** Lần hoàn tiền thủ công đã ghi nhận. */
    public record RefundView(Long id, java.math.BigDecimal amount, String note, Instant recordedAt) {
    }

    /** Số tiền còn hoàn được của hoá đơn = đã thu qua cổng − đã hoàn. */
    @Transactional(readOnly = true)
    public java.math.BigDecimal refundable(Long invoiceId) {
        return repository.sumSucceeded(invoiceId, vn.lemar.selfstorage.payment.domain.PaymentDirection.PAY)
                .subtract(repository.sumSucceeded(invoiceId,
                        vn.lemar.selfstorage.payment.domain.PaymentDirection.REFUND));
    }

    @Transactional(readOnly = true)
    public List<RefundView> refunds(Long invoiceId) {
        return repository.findByInvoiceIdAndDirectionOrderByIdAsc(invoiceId,
                        vn.lemar.selfstorage.payment.domain.PaymentDirection.REFUND).stream()
                .map(t -> new RefundView(t.getId(), t.getAmount(), t.getTransactionContent(), t.getPaidAt()))
                .toList();
    }

    /**
     * Ghi nhận khoản hoàn tiền FM đã chuyển cho khách. Caller (booking) kiểm quyền + khoá hoá đơn; ở đây chặn hoàn
     * vượt số đã thu qua cổng.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public RefundView recordManualRefund(Long invoiceId, java.math.BigDecimal amount, String note) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Số tiền hoàn phải lớn hơn 0");
        }
        if (amount.compareTo(refundable(invoiceId)) > 0) {
            throw new IllegalStateException("Số tiền hoàn vượt số đã thu qua cổng thanh toán của hoá đơn");
        }
        Instant now = Instant.now();
        String ref = "RF" + invoiceId + "-" + now.toEpochMilli();
        PaymentTransaction refund = repository.save(PaymentTransaction.manualRefund(invoiceId, ref, amount, note, now));
        return new RefundView(refund.getId(), refund.getAmount(), refund.getTransactionContent(), refund.getPaidAt());
    }

    /** Mã các giao dịch {@code Pending} của các hoá đơn — đầu vào huỷ phiên ở cổng. */
    @Transactional(readOnly = true)
    public List<String> pendingTxnRefs(Collection<Long> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return List.of();
        }
        return repository.findPendingTxnRefsByInvoiceIds(invoiceIds);
    }

    /**
     * Huỷ phiên ở cổng rồi đóng giao dịch phía mình. Cổng xác nhận đã huỷ → khoá row, còn {@code Pending} thì
     * {@code Failed} + {@link PaymentFailed}. Cổng không huỷ được (đã trả, lỗi mạng, cổng không hỗ trợ) → giữ
     * nguyên, webhook / đối soát quyết. Trả trạng thái sau cùng.
     */
    @Transactional
    public PaymentSnapshot cancelSession(String txnRef, String reason) {
        PaymentTransaction transaction = repository.findByVnpTxnRef(txnRef)
                .orElseThrow(() -> new IllegalArgumentException("Không có giao dịch " + txnRef));
        if (!transaction.isPending()) {
            return snapshot(transaction);
        }
        if (gateway.cancel(txnRef, reason)) {
            transaction.markFailed("Đã huỷ phiên: " + reason, null);
            events.publishEvent(new PaymentFailed(transaction.getInvoiceId(), txnRef, transaction.getAmount(),
                    transaction.getFailureReason(), transaction.getId()));
        }
        return snapshot(transaction);
    }

    /** Mốc cổng xác nhận thu tiền sớm nhất của hoá đơn ({@code paid_at} của giao dịch {@code Success}). */
    @Transactional(readOnly = true)
    public Optional<Instant> firstPaidAt(Long invoiceId) {
        return repository.findFirstPaidAt(invoiceId, PaymentStatus.SUCCESS);
    }

    /** Mã các giao dịch {@code Pending} đã quá cửa sổ phiên — đầu vào job đối soát. */
    @Transactional(readOnly = true)
    public List<String> stalePendingTxnRefs() {
        return repository.findTxnRefsByStatusCreatedBefore(PaymentStatus.PENDING, Instant.now().minus(SESSION_EXPIRY));
    }

    private static PaymentSnapshot snapshot(PaymentTransaction t) {
        return new PaymentSnapshot(t.getInvoiceId(), t.getVnpTxnRef(), t.getStatus().dbValue(), t.getFailureReason(),
                t.getCreatedAt());
    }

    /**
     * Xử lý IPN — nguồn xác nhận thanh toán duy nhất. Return URL chỉ để hiển thị cho khách,
     * không được dùng để cập nhật dữ liệu.
     */
    @Transactional
    public IpnResponse handleIpn(Map<String, String> params) {
        if (!gateway.verifySignature(params)) {
            LOG.warn("IPN bị từ chối vì sai chữ ký, vnp_TxnRef={}", params.get("vnp_TxnRef"));
            return IpnResponse.invalidSignature();
        }

        String txnRef = params.get("vnp_TxnRef");
        PaymentTransaction transaction = repository.findByVnpTxnRef(txnRef).orElse(null);
        if (transaction == null) {
            return IpnResponse.orderNotFound();
        }

        // Chữ ký đúng nhưng khác merchant nghĩa là giao dịch không thuộc hệ thống này.
        if (!properties.tmnCode().equals(params.get("vnp_TmnCode"))) {
            LOG.warn("IPN mang vnp_TmnCode lạ: {}", params.get("vnp_TmnCode"));
            return IpnResponse.orderNotFound();
        }

        // Đối chiếu với transaction.amount — giá trị này booking lấy từ invoice.amount trong DB lúc mở phiên
        // (InvoicePaymentService), không phải tham số do client gửi.
        if (!amountMatches(params.get("vnp_Amount"), transaction.getAmount())) {
            return IpnResponse.invalidAmount();
        }

        // Chốt chặn idempotency: VNPay retry tới 10 lần, lần thứ hai trở đi không được ghi lại.
        if (!transaction.isPending()) {
            if (gatewayReportedSuccess(params) && transaction.getStatus() == PaymentStatus.FAILED) {
                settleLate(transaction, params.get("vnp_TransactionNo"), parsePayDate(params.get("vnp_PayDate")),
                        toJson(params));
            }
            return IpnResponse.alreadyConfirmed();
        }

        String responsePayload = toJson(params);
        // Hai event dưới đây publish TRƯỚC khi transaction này commit — listener phải đăng ký
        // @TransactionalEventListener(AFTER_COMMIT); xem javadoc PaymentSucceeded/PaymentFailed.
        if (gatewayReportedSuccess(params)) {
            transaction.markSucceeded(
                    params.get("vnp_TransactionNo"), parsePayDate(params.get("vnp_PayDate")), responsePayload);
            events.publishEvent(new PaymentSucceeded(transaction.getInvoiceId(), txnRef,
                    transaction.getAmount(), transaction.getPaidAt(), transaction.getId()));
        } else {
            String reason = "vnp_ResponseCode=" + params.get("vnp_ResponseCode")
                    + ", vnp_TransactionStatus=" + params.get("vnp_TransactionStatus");
            transaction.markFailed(reason, responsePayload);
            events.publishEvent(new PaymentFailed(transaction.getInvoiceId(), txnRef,
                    transaction.getAmount(), reason, transaction.getId()));
        }
        return IpnResponse.success();
    }

    /** Cổng chỉ coi là thành công khi cả hai mã đều {@code 00}. */
    private boolean gatewayReportedSuccess(Map<String, String> params) {
        return SUCCESS_CODE.equals(params.get("vnp_ResponseCode"))
                && SUCCESS_CODE.equals(params.get("vnp_TransactionStatus"));
    }

    private boolean amountMatches(String rawAmount, BigDecimal expected) {
        if (rawAmount == null) {
            return false;
        }
        try {
            BigDecimal received = new BigDecimal(rawAmount).divide(AMOUNT_SCALE);
            return received.compareTo(expected) == 0;
        } catch (ArithmeticException | NumberFormatException e) {
            LOG.warn("IPN mang vnp_Amount không đọc được: {}", rawAmount);
            return false;
        }
    }

    /** Raw payload để đối soát — JSON nếu serialize được, fallback {@code toString} để không mất dữ liệu. */
    private String toJson(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            LOG.warn("Không serialize được payload IPN sang JSON, lưu dạng toString", e);
            return params.toString();
        }
    }

    /**
     * VNPay gửi PayDate dạng {@code yyyyMMddHHmmss} (GMT+7). Parse fail ở nhánh Success là anomaly
     * hiếm (chữ ký đã hợp lệ nên payload là thật từ VNPay) — log ERROR và fallback về **thời điểm
     * nhận IPN**. Lưu ý: đây là thời điểm nhận, không phải thời điểm trả tiền thật; {@code paid_at}
     * chỉ để hiển thị/đối chiếu, **không dùng để phán kịp/trễ deadline**.
     */
    private Instant parsePayDate(String rawPayDate) {
        if (rawPayDate == null) {
            LOG.error("IPN Success thiếu vnp_PayDate, fallback về thời điểm nhận IPN");
            return Instant.now();
        }
        try {
            return LocalDateTime.parse(rawPayDate, PAY_DATE).atZone(VNPAY_ZONE).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            LOG.error("IPN mang vnp_PayDate không đọc được (raw='{}'), fallback về thời điểm nhận IPN",
                    rawPayDate);
            return Instant.now();
        }
    }
}
