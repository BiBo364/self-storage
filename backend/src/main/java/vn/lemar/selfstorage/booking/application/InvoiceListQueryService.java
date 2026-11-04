package vn.lemar.selfstorage.booking.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.exception.InvoiceFilterException;
import vn.lemar.selfstorage.booking.repository.BookingReadRepository;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.payment.application.PaymentSession;

/**
 * Danh sách hoá đơn của khách (ownership) và danh sách hoá đơn của cơ sở cho FM.
 * Danh sách FM là read-only; trạng thái thanh toán lấy từ read model của payment.
 */
@Service
public class InvoiceListQueryService {

    private static final Set<String> INVOICE_STATUSES =
            Set.of("Unpaid", "Paid", "Canceled", "Expired");
    private static final Set<String> INVOICE_TYPES =
            Set.of("Deposit", "DepositSupplement", "Rental", "Extension", "Penalty", "Service");
    private static final Set<String> PAYMENT_STATUSES =
            Set.of("Pending", "Failed", "Success", "Duplicate");
    private static final int MAX_PAGE_SIZE = 100;

    private final BookingReadRepository readRepository;
    private final CurrentAccountProvider currentAccount;
    private final Access access;

    public InvoiceListQueryService(BookingReadRepository readRepository,
                                   CurrentAccountProvider currentAccount, Access access) {
        this.readRepository = readRepository;
        this.currentAccount = currentAccount;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<InvoiceListView> listOwn() {
        return readRepository.findCustomerInvoices(currentAccount.getCurrentAccountId());
    }

    /**
     * Một trang hoá đơn của cơ sở FM. Filter invoice type/status và trạng thái PAY gần nhất
     * được xử lý trong read model để total/page khớp với dữ liệu trả về.
     */
    @Transactional(readOnly = true)
    public FmInvoicePageView listForFacility(Long facilityId, String invoiceStatus, String type,
                                             String paymentStatus, Long olderThanMinutes,
                                             int page, int size) {
        access.can("invoice.read", facilityId);
        String normalizedInvoiceStatus = normalize(INVOICE_STATUSES, invoiceStatus, "Trạng thái hoá đơn");
        String normalizedType = normalize(INVOICE_TYPES, type, "Loại hoá đơn");
        String normalizedPaymentStatus = normalize(PAYMENT_STATUSES, paymentStatus, "Trạng thái thanh toán");
        if (olderThanMinutes != null && olderThanMinutes < 0) {
            throw new InvoiceFilterException("Số phút không hợp lệ: " + olderThanMinutes);
        }
        if (page < 0) {
            throw new InvoiceFilterException("Số trang không hợp lệ: " + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvoiceFilterException("Kích thước trang phải từ 1 đến " + MAX_PAGE_SIZE);
        }

        long total = readRepository.countFacilityInvoices(facilityId, normalizedInvoiceStatus,
                normalizedType, normalizedPaymentStatus, olderThanMinutes);
        List<BookingReadRepository.FacilityInvoiceRow> rows = readRepository.findFacilityInvoices(
                facilityId, normalizedInvoiceStatus, normalizedType, normalizedPaymentStatus,
                olderThanMinutes, (long) page * size, size);
        Instant now = Instant.now();
        List<FmInvoiceListView> items = rows.stream().map(row -> toView(row, now)).toList();
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new FmInvoicePageView(items, page, size, total, totalPages);
    }

    private FmInvoiceListView toView(BookingReadRepository.FacilityInvoiceRow row, Instant now) {
        boolean pending = "Pending".equals(row.paymentStatus());
        long pendingAgeMinutes = pending && row.paymentCreatedAt() != null
                ? Math.max(0, Duration.between(row.paymentCreatedAt(), now).toMinutes())
                : 0;
        boolean needsReconciliation = pending
                && pendingAgeMinutes >= PaymentSession.WINDOW.toMinutes();
        boolean duplicateRefundRequired = "Duplicate".equals(row.paymentStatus());
        return new FmInvoiceListView(row.id(), row.orderId(), row.code(), row.title(), row.description(),
                row.type(), row.status(), row.amount(), row.createdAt(), row.dueDate(), row.unitId(),
                row.unitCode(), row.facilityId(), row.facilityName(), row.customerId(), row.customerName(),
                row.customerEmail(), row.paymentStatus(), row.vnpTxnRef(), row.paymentCreatedAt(),
                row.paidAt(), pendingAgeMinutes, needsReconciliation, duplicateRefundRequired);
    }

    private static String normalize(Set<String> allowed, String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (!allowed.contains(normalized)) {
            throw new InvoiceFilterException(label + " không hợp lệ: " + value);
        }
        return normalized;
    }
}
