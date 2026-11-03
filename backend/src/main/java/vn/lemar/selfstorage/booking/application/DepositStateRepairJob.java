package vn.lemar.selfstorage.booking.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.payment.application.PaymentSessionQuery;
import vn.lemar.selfstorage.payment.application.event.PaymentSucceeded;

/**
 * Local outbox cho kết quả IPN: giao dịch {@code PAY/Success} là bản ghi bền; nếu listener booking
 * lỗi sau khi IPN commit, invoice vẫn {@code Unpaid}. Job quét invoice Unpaid có giao dịch thành
 * công và gọi lại listener (idempotent) — không cần querydr, không thêm bảng outbox.
 *
 * <p>Không cứu được IPN mất hẳn (giao dịch còn Pending); ca đó vẫn cần đối soát thủ công. Một
 * instance, chưa cần ShedLock.
 */
@Component
public class DepositStateRepairJob {

    private static final Logger LOG = LoggerFactory.getLogger(DepositStateRepairJob.class);

    private final InvoiceRepository invoices;
    private final PaymentSessionQuery payments;
    private final DepositPaymentListener listener;

    public DepositStateRepairJob(InvoiceRepository invoices, PaymentSessionQuery payments,
                                 DepositPaymentListener listener) {
        this.invoices = invoices;
        this.payments = payments;
        this.listener = listener;
    }

    @Scheduled(
            fixedDelayString = "${booking.deposit-repair.fixed-delay-ms:300000}",
            initialDelayString = "${booking.deposit-repair.initial-delay-ms:60000}")
    public void repairUnpaidDeposits() {
        List<Invoice> unpaid = invoices.findByStatusAndTypeIn(
                InvoiceStatus.UNPAID, List.of(InvoiceType.DEPOSIT, InvoiceType.DEPOSIT_SUPPLEMENT));
        for (Invoice invoice : unpaid) {
            payments.findSuccessfulPayment(invoice.getId()).ifPresent(payment -> {
                try {
                    listener.onPaymentSucceeded(new PaymentSucceeded(payment.invoiceId(),
                            payment.vnpTxnRef(), payment.amount(), payment.paidAt()));
                } catch (RuntimeException e) {
                    LOG.warn("Không sửa được state cho invoice {} từ giao dịch {}: {}",
                            invoice.getId(), payment.vnpTxnRef(), e.getMessage());
                }
            });
        }
    }
}
