package vn.lemar.selfstorage.booking.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.PaymentSessionCloser;

/**
 * Public API của booking cho hoá đơn gắn hợp đồng sau bàn giao (tenancy/checkout/overdue): gia hạn
 * ({@code Extension}), phí quá hạn và quyết toán trả kho ({@code Penalty}), phí dịch vụ ({@code Service}). Hoá đơn chỉ
 * gắn {@code contract_id} (CHECK V1); {@code orderId} truyền vào để lấy mã cơ sở cho mã hoá đơn.
 *
 * <p>Thanh toán đi qua {@code /api/invoices/{id}/pay}; khi {@code Paid}, booking phát {@link ContractInvoicePaid}.
 */
@Service
public class ContractInvoiceService {

    /** Tiền tố mã hoá đơn phí quá hạn — phân biệt với phạt trả kho trong cùng {@code type = Penalty}. */
    public static final String OVERDUE_KIND = "OVD";
    /** Mã hoá đơn quyết toán trả kho: hoá đơn chưa trả gộp vào quyết toán (phần đối trừ cọc) và phần còn thiếu. */
    public static final String SETTLEMENT_KIND = "PEN";
    /** Phí vệ sinh khi trả kho. */
    public static final String CLEANING_KIND = "CLN";
    /** Phí hư hỏng khi trả kho. */
    public static final String DAMAGE_KIND = "DMG";
    /** Phí mất chìa khi trả kho. */
    public static final String LOST_KEY_KIND = "KEY";
    /** Mọi tiền tố mã hoá đơn quyết toán trả kho — có một hoá đơn chưa huỷ mang mã này = đã chốt trả kho. */
    public static final Set<String> SETTLEMENT_KINDS =
            Set.of(CLEANING_KIND, DAMAGE_KIND, LOST_KEY_KIND, SETTLEMENT_KIND);

    private final InvoiceRepository invoiceRepository;
    private final RentalOrderRepository orderRepository;
    private final DepositInvoiceIssuer issuer;
    private final PaymentService paymentService;
    private final PaymentSessionCloser sessionCloser;
    private final AuditRecorder auditRecorder;

    ContractInvoiceService(InvoiceRepository invoiceRepository, RentalOrderRepository orderRepository,
                           DepositInvoiceIssuer issuer, PaymentService paymentService,
                           PaymentSessionCloser sessionCloser, AuditRecorder auditRecorder) {
        this.invoiceRepository = invoiceRepository;
        this.orderRepository = orderRepository;
        this.issuer = issuer;
        this.paymentService = paymentService;
        this.sessionCloser = sessionCloser;
        this.auditRecorder = auditRecorder;
    }

    /** Hoá đơn gắn hợp đồng. */
    public record ContractBill(Long invoiceId, String code, String type, String status, String title,
                               BigDecimal amount, Instant dueDate, Instant createdAt) {

        public boolean unpaid() {
            return InvoiceStatus.UNPAID.dbValue().equals(status);
        }
    }

    /**
     * Lập hoá đơn gắn hợp đồng.
     *
     * @param kind tiền tố mã ({@code EXT}, {@code PEN}, {@code OVD}, {@code SVC})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractBill issue(Long orderId, Long contractId, Long customerId, String type, String kind, String title,
                              String description, BigDecimal amount, Instant dueDate, Long actorAccountId) {
        RentalOrder order = orderRepository.findById(orderId).orElseThrow(RentalOrderNotFoundException::new);
        Invoice invoice = Invoice.forContract(contractId, customerId, InvoiceType.fromDbValue(type),
                issuer.code(order, Instant.now(), kind), title, description, amount, dueDate);
        invoiceRepository.save(invoice);
        auditRecorder.record(new AuditEntry(AuditAction.INVOICE_CREATED, AuditEntityType.INVOICE,
                String.valueOf(invoice.getId()), null,
                Map.of("code", invoice.getCode(), "type", invoice.getType().dbValue(), "status",
                        invoice.getStatus().dbValue(), "amount", amount, "contractId", contractId),
                null, actorAccountId));
        return bill(invoice);
    }

    @Transactional(readOnly = true)
    public List<ContractBill> bills(Long contractId) {
        return invoiceRepository.findByContractIdOrderByIdDesc(contractId).stream()
                .map(ContractInvoiceService::bill).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ContractBill> bill(Long invoiceId) {
        return invoiceRepository.findById(invoiceId).map(ContractInvoiceService::bill);
    }

    /** Hợp đồng đang có hoá đơn phí quá hạn {@code Unpaid} — cron đồng bộ lại (vd vừa gia hạn phủ kỳ quá hạn). */
    @Transactional(readOnly = true)
    public List<Long> contractsWithOpenOverdueFee() {
        return invoiceRepository.findUnpaidContractIdsByCodePrefix("INV-" + OVERDUE_KIND + "-%");
    }

    /** Có hoá đơn {@code Unpaid} loại {@code type} của hợp đồng không. */
    @Transactional(readOnly = true)
    public boolean hasUnpaid(Long contractId, String type) {
        return bills(contractId).stream().anyMatch(b -> b.unpaid() && b.type().equals(type));
    }

    /**
     * Flow 6 — phí quá hạn cộng dồn: {@code due = accrued − tổng phí quá hạn đã Paid}. Còn phải thu → cập nhật (hoặc
     * tạo) đúng một hoá đơn {@code INV-OVD} {@code Unpaid}; hết phải thu (gia hạn đã phủ kỳ quá hạn) → huỷ hoá đơn
     * đang mở. Hoá đơn đang có phiên thanh toán mở thì không đổi số tiền (cổng đã nhận số cũ).
     *
     * @return hoá đơn quá hạn đang mở sau khi đồng bộ, rỗng nếu không còn phải thu
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ContractBill> syncOverdueFee(Long orderId, Long contractId, Long customerId, BigDecimal accrued,
                                                 long days, Instant dueDate) {
        List<Invoice> all = invoiceRepository.findByContractIdOrderByIdDesc(contractId);
        // Đã chốt quyết toán trả kho (có hoá đơn INV-CLN/DMG/KEY/PEN): phí quá hạn đã gộp vào quyết toán — không
        // phát hành lại.
        if (all.stream().anyMatch(i -> isSettlementCode(i.getCode()) && i.getStatus() != InvoiceStatus.CANCELED)) {
            return Optional.empty();
        }
        List<Invoice> overdue = all.stream()
                .filter(i -> i.getType() == InvoiceType.PENALTY && i.getCode().startsWith("INV-" + OVERDUE_KIND))
                .toList();
        BigDecimal paid = overdue.stream().filter(i -> i.getStatus() == InvoiceStatus.PAID)
                .map(Invoice::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal due = accrued.subtract(paid);
        Invoice open = overdue.stream().filter(Invoice::isUnpaid).findFirst().orElse(null);
        if (open != null) {
            open = invoiceRepository.findByIdForUpdate(open.getId()).orElse(open);
            if (!paymentService.invoicesWithOpenSession(List.of(open.getId())).isEmpty()) {
                return Optional.of(bill(open));
            }
            if (due.signum() <= 0) {
                open.cancel();
                record(AuditAction.INVOICE_CANCELED, open, Map.of("status", InvoiceStatus.UNPAID.dbValue()),
                        Map.of("status", open.getStatus().dbValue()), "Không còn phí quá hạn phải thu");
                return Optional.empty();
            }
            if (open.getAmount().compareTo(due) != 0) {
                BigDecimal before = open.getAmount();
                open.reprice(due, dueDate);
                record(AuditAction.OVERDUE_FEE_ACCRUED, open, Map.of("amount", before),
                        Map.of("amount", due, "days", days), null);
            }
            return Optional.of(bill(open));
        }
        if (due.signum() <= 0) {
            return Optional.empty();
        }
        ContractBill created = issue(orderId, contractId, customerId, InvoiceType.PENALTY.dbValue(), OVERDUE_KIND,
                "Phí quá hạn hợp đồng", "Phí quá hạn " + days + " ngày sau thời gian ân hạn.", due, dueDate, null);
        Invoice invoice = invoiceRepository.findById(created.invoiceId()).orElseThrow();
        record(AuditAction.OVERDUE_FEE_ACCRUED, invoice, null, Map.of("amount", due, "days", days), null);
        return Optional.of(created);
    }

    /**
     * Quyết toán trả kho: huỷ các hoá đơn {@code Unpaid} của hợp đồng (gộp vào quyết toán) và trả tổng tiền của chúng.
     * Còn phiên thanh toán mở → 409 để FS thử lại sau khi phiên đóng.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal foldUnpaidIntoSettlement(Long contractId, String reason) {
        List<Invoice> unpaid = invoiceRepository.findByContractIdOrderByIdDesc(contractId).stream()
                .filter(Invoice::isUnpaid).toList();
        List<Long> ids = unpaid.stream().map(Invoice::getId).toList();
        if (!paymentService.invoicesWithOpenSession(ids).isEmpty()) {
            throw new BookingConflictException("Khách đang có phiên thanh toán chưa kết thúc cho hoá đơn của hợp "
                    + "đồng. Vui lòng thử lại sau ít phút.");
        }
        BigDecimal total = BigDecimal.ZERO;
        List<Long> canceled = new ArrayList<>();
        for (Invoice found : unpaid) {
            Invoice invoice = invoiceRepository.findByIdForUpdate(found.getId()).orElse(found);
            if (!invoice.isUnpaid()) {
                continue;
            }
            total = total.add(invoice.getAmount());
            invoice.cancel();
            canceled.add(invoice.getId());
            record(AuditAction.INVOICE_CANCELED, invoice, Map.of("status", InvoiceStatus.UNPAID.dbValue()),
                    Map.of("status", invoice.getStatus().dbValue()), reason);
        }
        sessionCloser.closeAfterCommit(canceled, "Gop vao quyet toan tra kho");
        return total;
    }

    /** Mã hoá đơn thuộc quyết toán trả kho ({@code INV-<CLN|DMG|KEY|PEN>-…}). */
    public static boolean isSettlementCode(String code) {
        return code != null && SETTLEMENT_KINDS.stream().anyMatch(kind -> code.startsWith("INV-" + kind + "-"));
    }

    /**
     * Hoá đơn quyết toán trả kho phần đã đối trừ tiền cọc → {@code Paid} ngay (không qua cổng).
     *
     * @param kind một trong {@link #SETTLEMENT_KINDS} — mỗi loại phí một hoá đơn
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractBill settleByDeposit(Long orderId, Long contractId, Long customerId, String kind,
                                        BigDecimal amount, String title, Long actorAccountId) {
        if (!SETTLEMENT_KINDS.contains(kind)) {
            throw new IllegalArgumentException("Loại hoá đơn quyết toán không hợp lệ: " + kind);
        }
        ContractBill issued = issue(orderId, contractId, customerId, InvoiceType.PENALTY.dbValue(), kind, title,
                "Đối trừ vào tiền cọc khi trả kho.", amount, Instant.now(), actorAccountId);
        Invoice invoice = invoiceRepository.findByIdForUpdate(issued.invoiceId()).orElseThrow();
        invoice.markPaid();
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("status", invoice.getStatus().dbValue());
        newValue.put("settledBy", "deposit");
        auditRecorder.record(new AuditEntry(AuditAction.INVOICE_PAID, AuditEntityType.INVOICE,
                String.valueOf(invoice.getId()), Map.of("status", InvoiceStatus.UNPAID.dbValue()), newValue,
                "Đối trừ tiền cọc", actorAccountId));
        return bill(invoice);
    }

    private void record(AuditAction action, Invoice invoice, Object oldValue, Object newValue, String reason) {
        auditRecorder.record(new AuditEntry(action, AuditEntityType.INVOICE, String.valueOf(invoice.getId()),
                oldValue, newValue, reason, null));
    }

    private static ContractBill bill(Invoice invoice) {
        return new ContractBill(invoice.getId(), invoice.getCode(), invoice.getType().dbValue(),
                invoice.getStatus().dbValue(), invoice.getTitle(), invoice.getAmount(), invoice.getDueDate(),
                invoice.getCreatedAt());
    }
}
