package vn.lemar.selfstorage.payment.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.payment.application.dto.PendingPaymentCasePageView;
import vn.lemar.selfstorage.payment.application.dto.PendingPaymentCaseView;
import vn.lemar.selfstorage.payment.application.dto.RefundCasePageView;
import vn.lemar.selfstorage.payment.application.dto.RefundCaseView;
import vn.lemar.selfstorage.payment.application.event.PaymentPendingReviewRequired;
import vn.lemar.selfstorage.payment.application.exception.PendingPaymentFilterException;
import vn.lemar.selfstorage.payment.repository.PaymentReconciliationCaseRepository;

/** Tạo/đọc/đóng case đối soát; không đổi trạng thái PaymentTransaction. */
@Service
public class PaymentReconciliationCaseService {

    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentReconciliationCaseRepository repository;
    private final ApplicationEventPublisher events;

    public PaymentReconciliationCaseService(PaymentReconciliationCaseRepository repository,
                                            ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /** Tạo case cho Pending chưa có case; trả số case mới. */
    @Transactional
    public int createMissingCases(Instant cutoff, int limit) {
        int created = 0;
        for (PaymentReconciliationCaseRepository.PendingRow row : repository.findWithoutCase(cutoff, limit)) {
            var caseId = repository.createIfAbsent(row.transactionId(), row.invoiceId(), Instant.now());
            if (caseId.isPresent()) {
                events.publishEvent(new PaymentPendingReviewRequired(caseId.get(), row.transactionId(),
                        row.invoiceId(), row.vnpTxnRef(), row.amount(), row.createdAt()));
                created++;
            }
        }
        return created;
    }

    @Transactional(readOnly = true)
    public PendingPaymentCasePageView listOpen(long olderThanMinutes, int page, int size) {
        if (olderThanMinutes < 0) {
            throw new PendingPaymentFilterException("Số phút không hợp lệ: " + olderThanMinutes);
        }
        validatePage(page, size);
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(olderThanMinutes));
        long total = repository.countOpen(cutoff);
        Instant now = Instant.now();
        List<PendingPaymentCaseView> items = repository.findOpenPage(cutoff, (long) page * size, size)
                .stream()
                .map(row -> new PendingPaymentCaseView(row.id(), row.transactionId(), row.invoiceId(),
                        row.vnpTxnRef(), row.amount(), row.transactionCreatedAt(), row.detectedAt(),
                        row.notifiedAt(), ageMinutes(row.transactionCreatedAt(), now), row.status(),
                        row.resolution(), row.gatewayTransactionNo(), row.paidAt(), row.note(),
                        row.evidenceUrl(), row.resolvedAt(), row.resolvedByEmail()))
                .toList();
        return new PendingPaymentCasePageView(items, page, size, total, totalPages(total, size));
    }

    @Transactional(readOnly = true)
    public RefundCasePageView listRefundRequired(int page, int size) {
        validatePage(page, size);
        long total = repository.countRefundRequired();
        List<RefundCaseView> items = repository.findRefundRequiredPage((long) page * size, size)
                .stream()
                .map(row -> new RefundCaseView(row.invoiceId(), row.invoiceCode(), row.orderId(),
                        row.amount(), row.refundStatus(), row.refundReason(), row.refundRequestedAt(),
                        row.vnpTxnRef(), row.gatewayTransactionNo(), row.paidAt()))
                .toList();
        return new RefundCasePageView(items, page, size, total, totalPages(total, size));
    }

    @Transactional
    public void markNotified(Long caseId) {
        repository.markNotified(caseId);
    }

    /** Đóng case nếu có; gọi trong transaction reconcile/IPN. */
    @Transactional
    public void resolve(Long transactionId, String resolution, String gatewayTransactionNo,
                        Instant paidAt, String note, String evidenceUrl, String actorEmail) {
        repository.resolveByTransaction(transactionId, resolution, gatewayTransactionNo, paidAt,
                note, evidenceUrl, actorEmail);
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new PendingPaymentFilterException("Số trang không hợp lệ: " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new PendingPaymentFilterException("Kích thước trang phải từ 1 đến " + MAX_PAGE_SIZE);
        }
    }

    private static int totalPages(long total, int size) {
        return total == 0 ? 0 : (int) ((total + size - 1) / size);
    }

    private static long ageMinutes(Instant createdAt, Instant now) {
        return Math.max(0, Duration.between(createdAt, now).toMinutes());
    }
}
