package vn.lemar.selfstorage.payment.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.payment.application.dto.SuccessfulPayment;
import vn.lemar.selfstorage.payment.domain.PaymentDirection;
import vn.lemar.selfstorage.payment.domain.PaymentStatus;
import vn.lemar.selfstorage.payment.repository.PaymentTransactionRepository;

/** Đọc trạng thái phiên từ chính bảng giao dịch — không có khóa riêng nào để lệch. */
@Service
public class PaymentSessionQueryService implements PaymentSessionQuery {

    private final PaymentTransactionRepository repository;
    private final PaymentUrlCache urlCache;

    public PaymentSessionQueryService(PaymentTransactionRepository repository, PaymentUrlCache urlCache) {
        this.repository = repository;
        this.urlCache = urlCache;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenSession(Collection<Long> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return false;
        }
        return repository.existsByInvoiceIdInAndStatusAndCreatedAtAfter(
                invoiceIds, PaymentStatus.PENDING, Instant.now().minus(PaymentSession.WINDOW));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasSuccessfulPayment(Long invoiceId) {
        return repository.existsByInvoiceIdAndDirectionAndStatus(
                invoiceId, PaymentDirection.PAY, PaymentStatus.SUCCESS);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenSessionForInvoice(Long invoiceId) {
        return hasOpenSession(List.of(invoiceId));
    }

    @Override
    public Optional<String> openPaymentUrl(Long invoiceId) {
        return urlCache.get(invoiceId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SuccessfulPayment> findSuccessfulPayment(Long invoiceId) {
        return repository.findFirstByInvoiceIdAndDirectionAndStatusOrderByPaidAtDesc(
                        invoiceId, PaymentDirection.PAY, PaymentStatus.SUCCESS)
                .map(transaction -> new SuccessfulPayment(
                        transaction.getInvoiceId(), transaction.getVnpTxnRef(),
                        transaction.getAmount(), transaction.getPaidAt()));
    }
}
