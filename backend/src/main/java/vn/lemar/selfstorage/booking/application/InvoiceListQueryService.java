package vn.lemar.selfstorage.booking.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.exception.InvoiceFilterException;
import vn.lemar.selfstorage.booking.repository.BookingReadRepository;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.payment.application.PaymentSession;
import vn.lemar.selfstorage.payment.application.PaymentSessionQuery;
import vn.lemar.selfstorage.payment.application.dto.PaymentStateView;

/**
 * Danh sách hoá đơn của khách (ownership) và danh sách hoá đơn của cơ sở cho FM.
 * Danh sách FM là read-only; trạng thái thanh toán lấy qua public query của module payment.
 */
@Service
public class InvoiceListQueryService {

    private static final Set<String> INVOICE_STATUSES =
            Set.of("Unpaid", "Paid", "Canceled", "Expired");
    private static final Set<String> PAYMENT_STATUSES =
            Set.of("Pending", "Failed", "Success", "Duplicate");

    private final BookingReadRepository readRepository;
    private final CurrentAccountProvider currentAccount;
    private final Access access;
    private final PaymentSessionQuery payments;

    public InvoiceListQueryService(BookingReadRepository readRepository,
                                   CurrentAccountProvider currentAccount, Access access,
                                   PaymentSessionQuery payments) {
        this.readRepository = readRepository;
        this.currentAccount = currentAccount;
        this.access = access;
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public List<InvoiceListView> listOwn() {
        return readRepository.findCustomerInvoices(currentAccount.getCurrentAccountId());
    }

    /**
     * Hoá đơn của cơ sở FM đang phụ trách. {@code paymentStatus} lọc theo giao dịch PAY gần nhất;
     * {@code olderThanMinutes} chỉ giữ giao dịch Pending đã quá số phút đó.
     */
    @Transactional(readOnly = true)
    public List<FmInvoiceListView> listForFacility(Long facilityId, String invoiceStatus,
                                                   String paymentStatus, Long olderThanMinutes) {
        access.can("invoice.read", facilityId);
        String normalizedInvoiceStatus = normalizeInvoiceStatus(invoiceStatus);
        String normalizedPaymentStatus = normalizePaymentStatus(paymentStatus);
        if (olderThanMinutes != null && olderThanMinutes < 0) {
            throw new InvoiceFilterException("Số phút không hợp lệ: " + olderThanMinutes);
        }

        List<BookingReadRepository.FacilityInvoiceRow> rows =
                readRepository.findFacilityInvoices(facilityId, normalizedInvoiceStatus);
        Map<Long, PaymentStateView> latest = payments.latestPayments(
                rows.stream().map(BookingReadRepository.FacilityInvoiceRow::id).toList());
        Instant now = Instant.now();

        return rows.stream()
                .map(row -> toView(row, latest.get(row.id()), now))
                .filter(view -> normalizedPaymentStatus == null
                        || normalizedPaymentStatus.equals(view.paymentStatus()))
                .filter(view -> olderThanMinutes == null
                        || (view.paymentStatus() != null && "Pending".equals(view.paymentStatus())
                        && view.pendingAgeMinutes() >= olderThanMinutes))
                .toList();
    }

    private FmInvoiceListView toView(BookingReadRepository.FacilityInvoiceRow row,
                                     PaymentStateView payment, Instant now) {
        String paymentStatus = payment == null ? null : payment.status();
        boolean pending = "Pending".equals(paymentStatus);
        long pendingAgeMinutes = pending && payment.createdAt() != null
                ? Math.max(0, Duration.between(payment.createdAt(), now).toMinutes())
                : 0;
        boolean needsReconciliation = pending
                && pendingAgeMinutes >= PaymentSession.WINDOW.toMinutes();
        boolean duplicateRefundRequired = "Duplicate".equals(paymentStatus);
        return new FmInvoiceListView(row.id(), row.orderId(), row.code(), row.title(), row.description(),
                row.type(), row.status(), row.amount(), row.createdAt(), row.dueDate(), row.unitId(),
                row.unitCode(), row.facilityId(), row.facilityName(), row.customerId(), row.customerName(),
                row.customerEmail(), paymentStatus, payment == null ? null : payment.vnpTxnRef(),
                payment == null ? null : payment.createdAt(), payment == null ? null : payment.paidAt(),
                pendingAgeMinutes, needsReconciliation, duplicateRefundRequired);
    }

    private static String normalizeInvoiceStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.strip();
        if (!INVOICE_STATUSES.contains(normalized)) {
            throw new InvoiceFilterException("Trạng thái hoá đơn không hợp lệ: " + status);
        }
        return normalized;
    }

    private static String normalizePaymentStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.strip();
        if (!PAYMENT_STATUSES.contains(normalized)) {
            throw new InvoiceFilterException("Trạng thái thanh toán không hợp lệ: " + status);
        }
        return normalized;
    }
}
