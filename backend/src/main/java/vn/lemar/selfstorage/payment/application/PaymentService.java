package vn.lemar.selfstorage.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.payment.application.dto.GatewayNotification;
import vn.lemar.selfstorage.payment.application.dto.GatewayQueryResult;
import vn.lemar.selfstorage.payment.application.dto.IpnResponse;
import vn.lemar.selfstorage.payment.application.dto.ManualReconciliationOutcome;
import vn.lemar.selfstorage.payment.application.dto.ManualReconciliationRequest;
import vn.lemar.selfstorage.payment.application.dto.ManualReconciliationResponse;
import vn.lemar.selfstorage.payment.application.dto.PaymentRequest;
import vn.lemar.selfstorage.payment.application.event.PaymentDuplicateDetected;
import vn.lemar.selfstorage.payment.application.event.PaymentFailed;
import vn.lemar.selfstorage.payment.application.event.PaymentSucceeded;
import vn.lemar.selfstorage.payment.application.exception.ManualReconciliationConflictException;
import vn.lemar.selfstorage.payment.application.exception.ManualReconciliationNotFoundException;
import vn.lemar.selfstorage.payment.application.exception.ManualReconciliationValidationException;
import vn.lemar.selfstorage.payment.config.VnPayProperties;
import vn.lemar.selfstorage.payment.domain.PaymentDirection;
import vn.lemar.selfstorage.payment.domain.PaymentStatus;
import vn.lemar.selfstorage.payment.domain.PaymentTransaction;
import vn.lemar.selfstorage.payment.repository.PaymentTransactionRepository;

/**
 * Điểm vào duy nhất để thu tiền. Flow 1 thu cọc, Flow 2 thu tháng đầu, Flow 3 thu gia hạn đều
 * gọi vào đây thay vì tự viết phần cổng.
 *
 * <p>Mọi nguồn xác nhận thu tiền (IPN VNPay, webhook payOS, {@code payment-check} của khách, đối soát
 * thủ công của ADMIN) đi chung một đường {@link #confirmSuccess}: khoá theo invoice, một invoice tối đa
 * một {@code PAY/Success}, thành công thứ hai thành {@code Duplicate}.
 */
@Service
public class PaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

    /** VNPay gửi số tiền đã nhân 100 nên nhận về phải chia lại. */
    private static final BigDecimal AMOUNT_SCALE = BigDecimal.valueOf(100);

    private static final String SUCCESS_CODE = "00";
    private static final ZoneId VNPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** Cửa sổ phiên ở cổng — khớp {@code vnp_ExpireDate} / {@code expiredAt} của payOS. */
    public static final Duration PAYMENT_WINDOW = PaymentSession.WINDOW;

    private final PaymentGateway gateway;
    private final PaymentTransactionRepository repository;
    private final VnPayProperties properties;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;
    private final PaymentUrlCache urlCache;
    private final PaymentReconciliationCaseService reconciliationCases;

    public PaymentService(PaymentGateway gateway, PaymentTransactionRepository repository,
                          VnPayProperties properties, ApplicationEventPublisher events,
                          ObjectMapper objectMapper, AuditRecorder audit, PaymentUrlCache urlCache,
                          PaymentReconciliationCaseService reconciliationCases) {
        this.gateway = gateway;
        this.repository = repository;
        this.properties = properties;
        this.events = events;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.urlCache = urlCache;
        this.reconciliationCases = reconciliationCases;
    }

    /** Kết quả xử lý thông báo thanh toán dạng body (webhook payOS). */
    public enum NotificationResult { ACCEPTED, ALREADY_SETTLED, NOT_FOUND, INVALID_SIGNATURE, INVALID_AMOUNT }

    /** Kết quả chốt một xác nhận thành công: giao dịch thành {@code Success} hay {@code Duplicate}. */
    private enum Confirmation { SUCCESS, DUPLICATE }

    /** Ảnh chụp trạng thái giao dịch cho caller (không lộ entity ra ngoài module). */
    public record PaymentSnapshot(Long invoiceId, String txnRef, String status, String failureReason,
                                  Instant createdAt) {
    }

    /** Lần hoàn tiền thủ công đã ghi nhận. */
    public record RefundView(Long id, BigDecimal amount, String note, Instant recordedAt) {
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
        // vnp_CreateDate = created_at của giao dịch: tra cứu cổng và resumePayment cần đúng mốc này.
        String paymentUrl = gateway.buildPaymentUrl(request.withCreatedAt(transaction.getCreatedAt()));
        urlCache.put(invoiceId, paymentUrl);
        return paymentUrl;
    }

    /**
     * Mở phiên cho một hoá đơn bằng cổng đang dùng: cổng sinh mã giao dịch (payOS cần mã số) rồi tạo phiên như
     * {@link #startPayment}.
     */
    @Transactional
    public String startInvoicePayment(Long invoiceId, BigDecimal amount, String orderInfo, String ipAddr) {
        return startPayment(invoiceId, new PaymentRequest(gateway.newTxnRef(invoiceId), amount, orderInfo, ipAddr));
    }

    /**
     * Dựng lại URL của phiên Pending còn hạn khi cache URL bị mất. Không tạo giao dịch mới:
     * cùng {@code vnp_TxnRef} và cùng {@code createdAt}, nên hạn phiên không bị kéo dài.
     *
     * @return URL nếu còn phiên trong 15 phút; rỗng nếu phiên đã hết hạn/không còn hoặc cổng không dựng lại được
     */
    @Transactional(readOnly = true)
    public Optional<String> resumePayment(Long invoiceId, String clientIp) {
        Instant now = Instant.now();
        Optional<PaymentTransaction> pending = repository
                .findFirstByInvoiceIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                        invoiceId, PaymentStatus.PENDING, now.minus(PaymentSession.WINDOW));
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        PaymentTransaction transaction = pending.get();
        Duration remaining = Duration.between(now, transaction.getCreatedAt().plus(PaymentSession.WINDOW));
        if (remaining.isNegative() || remaining.isZero()) {
            return Optional.empty();
        }

        PaymentRequest request = new PaymentRequest(transaction.getVnpTxnRef(), transaction.getAmount(),
                transaction.getTransactionContent(), clientIp);
        String paymentUrl;
        try {
            paymentUrl = gateway.buildPaymentUrl(request, transaction.getCreatedAt());
        } catch (RuntimeException e) {
            // payOS không tạo lại link cho cùng orderCode — coi như mất phiên, caller trả 409.
            LOG.warn("Không dựng lại được URL phiên {}: {}", transaction.getVnpTxnRef(), e.getMessage());
            return Optional.empty();
        }
        if (paymentUrl == null || paymentUrl.isBlank()) {
            return Optional.empty();
        }
        urlCache.put(invoiceId, paymentUrl, remaining);
        return Optional.of(paymentUrl);
    }

    /**
     * Xử lý IPN — nguồn xác nhận thanh toán chính. Return URL chỉ để hiển thị cho khách,
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

        // Số tiền neo vào transaction.amount do caller truyền lúc startPayment — booking đọc từ Invoice
        // trong DB; payment không import booking nên không tự đọc lại.
        if (!amountMatches(params.get("vnp_Amount"), transaction.getAmount())) {
            return IpnResponse.invalidAmount();
        }

        String responsePayload = toJson(params);
        // Chốt chặn idempotency: VNPay retry tới 10 lần, lần thứ hai trở đi không được ghi lại.
        if (!transaction.isPending()) {
            if (gatewayReportedSuccess(params) && transaction.getStatus() == PaymentStatus.FAILED) {
                settleLate(transaction, params.get("vnp_TransactionNo"),
                        parsePayDate(params.get("vnp_PayDate")), responsePayload);
            }
            return IpnResponse.alreadyConfirmed();
        }

        // Các event dưới đây publish TRƯỚC khi transaction này commit — listener phải đăng ký
        // @TransactionalEventListener(AFTER_COMMIT); xem javadoc PaymentSucceeded/PaymentFailed/
        // PaymentDuplicateDetected.
        if (gatewayReportedSuccess(params)) {
            Confirmation result = confirmSuccess(transaction, params.get("vnp_TransactionNo"),
                    parsePayDate(params.get("vnp_PayDate")), responsePayload, "IPN", null, null, "IPN");
            return result == Confirmation.DUPLICATE ? IpnResponse.alreadyConfirmed() : IpnResponse.success();
        }
        String reason = "vnp_ResponseCode=" + params.get("vnp_ResponseCode")
                + ", vnp_TransactionStatus=" + params.get("vnp_TransactionStatus");
        fail(transaction, reason, responsePayload, reason, "IPN");
        return IpnResponse.success();
    }

    /**
     * Webhook payOS: kiểm chữ ký → tìm giao dịch (khoá row) → số tiền → còn {@code Pending} thì chốt như IPN.
     * Idempotent theo mã giao dịch.
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
        Instant paidAt = notification.paidAt() == null ? Instant.now() : notification.paidAt();
        if (!transaction.isPending()) {
            if (notification.success() && transaction.getStatus() == PaymentStatus.FAILED) {
                // Phiên đã huỷ/đóng phía mình nhưng khách vẫn chuyển khoản (vd quét QR cũ) — tiền đã vào.
                settleLate(transaction, notification.transactionNo(), paidAt, notification.payload());
            }
            return NotificationResult.ALREADY_SETTLED;
        }
        if (notification.success()) {
            confirmSuccess(transaction, notification.transactionNo(), paidAt, notification.payload(),
                    "webhook", null, null, "webhook");
        } else {
            String reason = "Cổng báo thanh toán không thành công";
            fail(transaction, reason, notification.payload(), reason, "webhook");
        }
        return NotificationResult.ACCEPTED;
    }

    /**
     * Chốt thủ công một giao dịch Pending theo kết quả ops tra portal cổng.
     *
     * <p>Vẫn khoá row, chỉ nhận Pending, ghi audit và phát event như IPN. Không sửa DB tay và không
     * tự mở lại invoice/order đã huỷ.
     */
    @Transactional
    public ManualReconciliationResponse reconcileManually(String txnRef,
            ManualReconciliationRequest request, String actorEmail) {
        PaymentTransaction transaction = repository.findByVnpTxnRef(txnRef)
                .orElseThrow(() -> new ManualReconciliationNotFoundException(txnRef));
        if (!transaction.isPending()) {
            throw new ManualReconciliationConflictException(transaction.getStatus());
        }

        String responsePayload = manualPayload(request, actorEmail);
        if (request.outcome() == ManualReconciliationOutcome.SUCCESS) {
            if (request.paidAt() == null) {
                throw new ManualReconciliationValidationException(
                        "paidAt bắt buộc khi outcome=SUCCESS");
            }
            if (request.paidAt().isAfter(Instant.now())) {
                throw new ManualReconciliationValidationException(
                        "paidAt không được ở tương lai");
            }
            if (request.paidAt().isBefore(transaction.getCreatedAt().truncatedTo(ChronoUnit.SECONDS))) {
                throw new ManualReconciliationValidationException(
                        "paidAt không được trước thời điểm tạo giao dịch");
            }
            String gatewayTransactionNo = request.gatewayTransactionNo();
            if (gatewayTransactionNo == null || gatewayTransactionNo.isBlank()) {
                throw new ManualReconciliationValidationException(
                        "gatewayTransactionNo bắt buộc khi outcome=SUCCESS");
            }
            gatewayTransactionNo = gatewayTransactionNo.trim();
            repository.lockInvoiceForPayment(transaction.getInvoiceId());
            ensureGatewayTransactionNoIsUnused(gatewayTransactionNo);
            confirmSuccess(transaction, gatewayTransactionNo, request.paidAt(), responsePayload,
                    request.note(), request.evidenceUrl(), actorEmail, actorEmail);
            try {
                repository.flush();
            } catch (DataIntegrityViolationException e) {
                if (!isUniqueViolation(e)) {
                    throw e;
                }
                // Unique constraint là chốt cuối khi hai request vượt qua pre-check cùng lúc.
                throw new ManualReconciliationConflictException(
                        "Dữ liệu giao dịch xung đột, không chốt được");
            }
        } else {
            transaction.markFailed("manual: " + request.note(), responsePayload);
            recordFailure(transaction, actorEmail == null ? "manual reconcile"
                    : "manual reconcile by " + actorEmail);
            reconciliationCases.resolve(transaction.getId(), "Failed", null, null,
                    request.note(), request.evidenceUrl(), actorEmail);
        }
        return new ManualReconciliationResponse(transaction.getVnpTxnRef(), transaction.getStatus(),
                transaction.getGatewayTransactionNo(), transaction.getPaidAt());
    }

    /** Giao dịch mới nhất của hoá đơn, không gọi cổng. */
    @Transactional(readOnly = true)
    public Optional<PaymentSnapshot> latest(Long invoiceId) {
        return repository.findFirstByInvoiceIdOrderByCreatedAtDescIdDesc(invoiceId).map(PaymentService::snapshot);
    }

    /**
     * Khách quay về từ cổng ({@code payment-check}): hỏi cổng về giao dịch mới nhất của hoá đơn. Giao dịch đã chốt
     * thì trả nguyên trạng, không gọi cổng.
     */
    @Transactional
    public Optional<PaymentSnapshot> reconcileLatest(Long invoiceId, String ipAddr) {
        return repository.findFirstByInvoiceIdOrderByCreatedAtDescIdDesc(invoiceId)
                .map(t -> reconcile(t.getVnpTxnRef(), ipAddr));
    }

    /**
     * Hỏi cổng trạng thái thật của một giao dịch {@code Pending} ({@code querydr} / tra cứu payOS) — kênh
     * server-to-server có chữ ký. Cổng xác nhận đã trả → chốt như IPN; cổng báo lỗi/huỷ → {@code Failed}.
     * Cổng chưa có kết quả, lỗi mạng, sai chữ ký, lệch số tiền → giữ {@code Pending}: không đóng phiên theo
     * đồng hồ, case đối soát của watchdog lo phần còn lại.
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
            LOG.warn("Tra cứu cổng {} lỗi, giữ Pending: {}", txnRef, e.getMessage());
            return snapshot(transaction);
        }
        if (result == null) {
            return snapshot(transaction);
        }
        String payload = toJson(result.payload());
        if (result.paid()) {
            if (result.amount() == null || result.amount().compareTo(transaction.getAmount()) != 0) {
                LOG.error("Tra cứu cổng {}: số tiền cổng ({}) lệch giao dịch ({}) — giữ Pending để xử lý thủ công",
                        txnRef, result.amount(), transaction.getAmount());
                return snapshot(transaction);
            }
            confirmSuccess(transaction, result.transactionNo(), parsePayDate(result.payDate()), payload,
                    "payment-check", null, null, "payment-check");
        } else if (result.failed()) {
            String reason = "Tra cứu cổng: trạng thái " + result.transactionStatus();
            fail(transaction, reason, payload, reason, "payment-check");
        }
        return snapshot(transaction);
    }

    /**
     * Hoá đơn nào trong danh sách đang có phiên thanh toán mở: còn giao dịch {@code Pending} tạo trong
     * {@link PaymentSession#WINDOW} — cùng một khoá với {@link PaymentSessionQuery}.
     */
    @Transactional(readOnly = true)
    public List<Long> invoicesWithOpenSession(Collection<Long> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return List.of();
        }
        return repository.findInvoiceIdsWithStatusSince(invoiceIds, PaymentStatus.PENDING,
                Instant.now().minus(PaymentSession.WINDOW));
    }

    /** Mã các giao dịch {@code Pending} của các hoá đơn (mọi tuổi) — đầu vào huỷ phiên ở cổng. */
    @Transactional(readOnly = true)
    public List<String> pendingTxnRefs(Collection<Long> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return List.of();
        }
        return repository.findPendingTxnRefsByInvoiceIds(invoiceIds);
    }

    /**
     * Huỷ phiên ở cổng rồi đóng giao dịch phía mình. Cổng xác nhận đã huỷ → khoá row, còn {@code Pending} thì
     * {@code Failed} + {@link PaymentFailed}. Cổng không huỷ được (VNPay không hỗ trợ, đã trả, lỗi mạng) → giữ
     * nguyên. Trả trạng thái sau cùng.
     */
    @Transactional
    public PaymentSnapshot cancelSession(String txnRef, String reason) {
        PaymentTransaction transaction = repository.findByVnpTxnRef(txnRef)
                .orElseThrow(() -> new IllegalArgumentException("Không có giao dịch " + txnRef));
        if (!transaction.isPending()) {
            return snapshot(transaction);
        }
        if (gateway.cancel(txnRef, reason)) {
            String closed = "Đã huỷ phiên: " + reason;
            fail(transaction, closed, null, closed, "cancel-session");
        }
        return snapshot(transaction);
    }

    /** Mốc cổng xác nhận thu tiền sớm nhất của hoá đơn ({@code paid_at} của giao dịch {@code Success}). */
    @Transactional(readOnly = true)
    public Optional<Instant> firstPaidAt(Long invoiceId) {
        return repository.findFirstPaidAt(invoiceId, PaymentStatus.SUCCESS);
    }

    /**
     * Số tiền còn hoàn được của hoá đơn = tiền đã vào qua cổng ({@code PAY} {@code Success} + {@code Duplicate})
     * − đã hoàn ({@code REFUND} {@code Success}). Hoá đơn không thu qua cổng có số này = 0.
     */
    @Transactional(readOnly = true)
    public BigDecimal refundable(Long invoiceId) {
        return repository.sumSucceeded(invoiceId, PaymentDirection.PAY)
                .add(repository.sumByStatus(invoiceId, PaymentDirection.PAY, PaymentStatus.DUPLICATE))
                .subtract(repository.sumSucceeded(invoiceId, PaymentDirection.REFUND));
    }

    @Transactional(readOnly = true)
    public List<RefundView> refunds(Long invoiceId) {
        return repository.findByInvoiceIdAndDirectionOrderByIdAsc(invoiceId, PaymentDirection.REFUND).stream()
                .map(t -> new RefundView(t.getId(), t.getAmount(), t.getTransactionContent(), t.getPaidAt()))
                .toList();
    }

    /**
     * Ghi nhận khoản hoàn tiền FM đã chuyển cho khách. Caller (booking) kiểm quyền + khoá hoá đơn + cập nhật
     * {@code refund_status}; ở đây chặn hoàn vượt số đã thu qua cổng.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RefundView recordManualRefund(Long invoiceId, BigDecimal amount, String note) {
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

    /**
     * Một nguồn đã xác nhận thu tiền cho giao dịch đang {@code Pending}. Khoá theo invoice (advisory lock) rồi kiểm
     * {@code PAY/Success}: chưa có → {@code Success} + {@link PaymentSucceeded}; đã có → {@code Duplicate} +
     * {@link PaymentDuplicateDetected}. Đóng case đối soát nếu có.
     */
    private Confirmation confirmSuccess(PaymentTransaction transaction, String gatewayTransactionNo, Instant paidAt,
                                        String responsePayload, String note, String evidenceUrl,
                                        String actorEmail, String resolvedBy) {
        repository.lockInvoiceForPayment(transaction.getInvoiceId());
        String previous = transaction.getStatus().dbValue();
        if (repository.existsByInvoiceIdAndDirectionAndStatus(transaction.getInvoiceId(),
                PaymentDirection.PAY, PaymentStatus.SUCCESS)) {
            transaction.markDuplicate(gatewayTransactionNo, paidAt,
                    "duplicate payment - refund required", responsePayload);
            recordDuplicate(transaction, previous, actorEmail);
            reconciliationCases.resolve(transaction.getId(), "Duplicate", gatewayTransactionNo, paidAt,
                    actorEmail == null ? note + " duplicate" : note, evidenceUrl, resolvedBy);
            return Confirmation.DUPLICATE;
        }
        transaction.markSucceeded(gatewayTransactionNo, paidAt, responsePayload);
        recordSuccess(transaction, previous, actorEmail);
        reconciliationCases.resolve(transaction.getId(), "Success", gatewayTransactionNo, paidAt,
                note, evidenceUrl, resolvedBy);
        return Confirmation.SUCCESS;
    }

    /**
     * Tiền vào trên giao dịch đã đóng {@code Failed} (phiên đã huỷ / cổng báo lỗi trước đó): tiền đã vào thật nên
     * không bỏ qua. Invoice chưa có {@code PAY/Success} → {@code Success} + {@link PaymentSucceeded} (booking chốt
     * nếu còn chờ, không thì đánh dấu cần hoàn); đã có → {@code Duplicate}. Gọi lặp không ghi lại: giao dịch đã rời
     * {@code Failed} thì caller không vào đây.
     */
    private void settleLate(PaymentTransaction transaction, String transactionNo, Instant paidAt, String payload) {
        LOG.error("Thanh toán muộn: giao dịch {} đã đóng ({}) nhưng cổng báo đã thu {} — chốt / hoàn thủ công",
                transaction.getVnpTxnRef(), transaction.getFailureReason(), transaction.getAmount());
        repository.lockInvoiceForPayment(transaction.getInvoiceId());
        if (repository.existsByInvoiceIdAndDirectionAndStatus(transaction.getInvoiceId(),
                PaymentDirection.PAY, PaymentStatus.SUCCESS)) {
            transaction.markDuplicate(transactionNo, paidAt, "late duplicate payment - refund required", payload);
            recordDuplicate(transaction, PaymentStatus.FAILED.dbValue(), null);
            return;
        }
        transaction.markLateSucceeded(transactionNo, paidAt, payload);
        recordSuccess(transaction, PaymentStatus.FAILED.dbValue(), null);
    }

    private void fail(PaymentTransaction transaction, String reason, String responsePayload, String auditReason,
                      String resolvedBy) {
        transaction.markFailed(reason, responsePayload);
        recordFailure(transaction, auditReason);
        reconciliationCases.resolve(transaction.getId(), "Failed", null, null, reason, null, resolvedBy);
    }

    private void recordSuccess(PaymentTransaction transaction, String previousStatus, String actorEmail) {
        // Audit entity của chính module này; RentalOrder/StorageUnit/Invoice do booking tự ghi.
        audit.record(new AuditEntry(AuditAction.PAYMENT_SUCCEEDED,
                AuditEntityType.PAYMENT_TRANSACTION, String.valueOf(transaction.getId()),
                Map.of("status", previousStatus),
                Map.of("status", "Success", "invoiceId", String.valueOf(transaction.getInvoiceId()),
                        "amount", transaction.getAmount().toPlainString(),
                        "gatewayTransactionNo", String.valueOf(transaction.getGatewayTransactionNo()),
                        "paidAt", String.valueOf(transaction.getPaidAt())),
                actorEmail == null ? null : "manual reconcile by " + actorEmail,
                null));
        events.publishEvent(new PaymentSucceeded(transaction.getInvoiceId(),
                transaction.getVnpTxnRef(), transaction.getAmount(), transaction.getPaidAt(), transaction.getId()));
    }

    /**
     * Giao dịch thành công thứ hai cho cùng invoice: giữ dấu vết tiền đã vào nhưng không ghi
     * Success lần hai, không phát PaymentSucceeded. Ops hoàn tiền thủ công.
     */
    private void recordDuplicate(PaymentTransaction transaction, String previousStatus, String actorEmail) {
        audit.record(new AuditEntry(AuditAction.PAYMENT_DUPLICATE,
                AuditEntityType.PAYMENT_TRANSACTION, String.valueOf(transaction.getId()),
                Map.of("status", previousStatus),
                Map.of("status", "Duplicate",
                        "gatewayTransactionNo", String.valueOf(transaction.getGatewayTransactionNo()),
                        "paidAt", String.valueOf(transaction.getPaidAt())),
                actorEmail == null ? "IPN duplicate payment" : "duplicate payment by " + actorEmail,
                null));
        events.publishEvent(new PaymentDuplicateDetected(transaction.getInvoiceId(),
                transaction.getVnpTxnRef(), transaction.getAmount(), transaction.getPaidAt()));
    }

    private void recordFailure(PaymentTransaction transaction, String auditReason) {
        audit.record(new AuditEntry(AuditAction.PAYMENT_FAILED,
                AuditEntityType.PAYMENT_TRANSACTION, String.valueOf(transaction.getId()),
                Map.of("status", "Pending"),
                Map.of("status", "Failed", "invoiceId", String.valueOf(transaction.getInvoiceId()),
                        "txnRef", transaction.getVnpTxnRef()),
                auditReason, null));
        events.publishEvent(new PaymentFailed(transaction.getInvoiceId(), transaction.getVnpTxnRef(),
                transaction.getAmount(), transaction.getFailureReason(), transaction.getId()));
    }

    private String manualPayload(ManualReconciliationRequest request, String actorEmail) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("source", "manual-portal");
        payload.put("outcome", request.outcome().name());
        payload.put("note", request.note());
        payload.put("actor", actorEmail == null ? "unknown" : actorEmail);
        if (request.gatewayTransactionNo() != null && !request.gatewayTransactionNo().isBlank()) {
            payload.put("gatewayTransactionNo", request.gatewayTransactionNo().trim());
        }
        return toJson(payload);
    }

    /** Unique constraint bảo vệ DB, nhưng trả 409 thay vì để lỗi ràng buộc thành 500. */
    private void ensureGatewayTransactionNoIsUnused(String gatewayTransactionNo) {
        if (gatewayTransactionNo != null && !gatewayTransactionNo.isBlank()
                && repository.existsByGatewayTransactionNo(gatewayTransactionNo)) {
            throw new ManualReconciliationConflictException(
                    "gatewayTransactionNo đã được dùng cho giao dịch khác");
        }
    }

    private static PaymentSnapshot snapshot(PaymentTransaction t) {
        return new PaymentSnapshot(t.getInvoiceId(), t.getVnpTxnRef(), t.getStatus().dbValue(), t.getFailureReason(),
                t.getCreatedAt());
    }

    /** Cổng chỉ coi là thành công khi cả hai mã đều {@code 00}. */
    private boolean gatewayReportedSuccess(Map<String, String> params) {
        return SUCCESS_CODE.equals(params.get("vnp_ResponseCode"))
                && SUCCESS_CODE.equals(params.get("vnp_TransactionStatus"));
    }

    private boolean isUniqueViolation(DataIntegrityViolationException exception) {
        return exception.getMostSpecificCause() instanceof SQLException sql
                && "23505".equals(sql.getSQLState());
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
            LOG.warn("Không serialize được payload sang JSON, lưu dạng toString", e);
            return params.toString();
        }
    }

    /**
     * VNPay gửi PayDate dạng {@code yyyyMMddHHmmss} (GMT+7). Parse fail ở nhánh Success là anomaly
     * hiếm (chữ ký đã hợp lệ nên payload là thật từ cổng) — log ERROR và fallback về **thời điểm
     * nhận**. {@code paid_at} chỉ để hiển thị/đối chiếu, **không dùng để phán kịp/trễ deadline**.
     */
    private Instant parsePayDate(String rawPayDate) {
        if (rawPayDate == null) {
            LOG.error("Xác nhận Success thiếu vnp_PayDate, fallback về thời điểm nhận");
            return Instant.now();
        }
        try {
            return LocalDateTime.parse(rawPayDate, PAY_DATE).atZone(VNPAY_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            LOG.error("vnp_PayDate không đọc được (raw='{}'), fallback về thời điểm nhận", rawPayDate);
            return Instant.now();
        }
    }
}
