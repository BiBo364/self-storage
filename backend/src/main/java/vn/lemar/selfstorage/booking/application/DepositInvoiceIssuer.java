package vn.lemar.selfstorage.booking.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.facility.application.StorageUnitView;
import vn.lemar.selfstorage.pricing.application.PricingQuery;

/**
 * Lập hoá đơn đặt cọc khi khách đồng ý proposal ({@code specs/draft.md} › 1.3 › Đồng ý).
 *
 * <p>Số tiền theo chốt #113: giá thuê hiệu lực × {@code invoice.deposit_months}. Hạn thanh toán
 * {@code due_date = min(now + invoice.deposit_due_days, RentalOrder.expires_at)}. Đổi khoang sau khi đã cọc
 * dùng {@link #issueSupplemental} cho phần chênh lệch (behavior-gap #165).
 */
@Component
class DepositInvoiceIssuer {

    static final String POLICY_DEPOSIT_MONTHS = "invoice.deposit_months";
    static final String POLICY_DEPOSIT_DUE_DAYS = "invoice.deposit_due_days";

    /** Ngày trong mã hoá đơn tính theo giờ Việt Nam — schema chưa có cột múi giờ của cơ sở. */
    private static final ZoneId CODE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_RANDOM_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();
    private final InvoiceRepository invoiceRepository;
    private final RentalOrderRepository orderRepository;
    private final FacilityAccess facilityAccess;
    private final PricingQuery pricingQuery;
    private final AuditRecorder auditRecorder;

    DepositInvoiceIssuer(InvoiceRepository invoiceRepository, RentalOrderRepository orderRepository,
                         FacilityAccess facilityAccess, PricingQuery pricingQuery, AuditRecorder auditRecorder) {
        this.invoiceRepository = invoiceRepository;
        this.orderRepository = orderRepository;
        this.facilityAccess = facilityAccess;
        this.pricingQuery = pricingQuery;
        this.auditRecorder = auditRecorder;
    }

    /** Tiền cọc đầy đủ của khoang: giá thuê hiệu lực × {@code invoice.deposit_months}. */
    BigDecimal depositAmount(StorageUnitView unit) {
        return pricingQuery.effectiveMonthlyPrice(unit.id())
                .multiply(BigDecimal.valueOf(pricingQuery.intPolicy(POLICY_DEPOSIT_MONTHS)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    Invoice issue(RentalOrder order, StorageUnitView unit, Long actorAccountId) {
        return create(order, unit, depositAmount(unit), InvoiceType.DEPOSIT,
                "Đặt cọc khoang chứa " + unit.code(),
                "Thanh toán đặt cọc khoang chứa " + unit.code() + " để đảm bảo giữ chỗ.",
                actorAccountId);
    }

    /** Hoá đơn bổ sung phần cọc còn thiếu khi đổi khoang sau khi đã cọc. */
    Invoice issueSupplemental(RentalOrder order, StorageUnitView unit, BigDecimal amount, Long actorAccountId) {
        return create(order, unit, amount, InvoiceType.DEPOSIT_SUPPLEMENT,
                "Bổ sung cọc khoang chứa " + unit.code(),
                "Thanh toán phần chênh lệch cọc khi đổi sang khoang chứa " + unit.code() + ".",
                actorAccountId);
    }

    /**
     * Spec 2.3: hoá đơn tháng đầu {@code INV-RNT-…} gắn hợp đồng, hạn do caller tính
     * ({@code min(now + handover.payment_grace_hours, expires_at)}).
     */
    Invoice issueFirstMonth(RentalOrder order, Long contractId, String unitCode, BigDecimal amount,
                            Instant dueDate, Long actorAccountId) {
        Instant now = Instant.now();
        Invoice invoice = Invoice.forContract(contractId, order.getCustomerId(), InvoiceType.RENTAL,
                code(order, now, "RNT"),
                "Tiền thuê tháng đầu khoang " + unitCode,
                "Thanh toán tiền thuê tháng đầu theo hợp đồng để nhận bàn giao khoang " + unitCode + ".",
                amount, dueDate);
        invoiceRepository.save(invoice);
        auditRecorder.record(new AuditEntry(AuditAction.INVOICE_CREATED, AuditEntityType.INVOICE,
                String.valueOf(invoice.getId()), null,
                Map.of("code", invoice.getCode(), "type", invoice.getType().dbValue(),
                        "status", invoice.getStatus().dbValue(), "amount", invoice.getAmount(),
                        "orderId", order.getId(), "contractId", contractId, "dueDate", dueDate.toString()),
                null, actorAccountId));
        return invoice;
    }

    private Invoice create(RentalOrder order, StorageUnitView unit, BigDecimal amount, InvoiceType type,
                           String title, String description, Long actorAccountId) {
        Instant now = Instant.now();
        Instant dueDate = now.plus(Duration.ofDays(pricingQuery.intPolicy(POLICY_DEPOSIT_DUE_DAYS)));
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(dueDate)) {
            dueDate = order.getExpiresAt();
        }

        Invoice invoice = type == InvoiceType.DEPOSIT
                ? Invoice.deposit(order, code(order, now, "DEP"), title, description, amount, dueDate)
                : Invoice.depositSupplement(order, code(order, now, "DEP"), title, description, amount, dueDate);
        invoiceRepository.save(invoice);
        auditRecorder.record(new AuditEntry(AuditAction.INVOICE_CREATED, AuditEntityType.INVOICE,
                String.valueOf(invoice.getId()), null,
                Map.of("code", invoice.getCode(), "type", invoice.getType().dbValue(),
                        "status", invoice.getStatus().dbValue(), "amount", invoice.getAmount(),
                        "orderId", order.getId(), "dueDate", dueDate.toString()),
                null, actorAccountId));
        return invoice;
    }

    /** Spec: {@code INV-{DEP|RNT}-{facility_code}-{YYMMDD}-{rand}}. */
    String code(RentalOrder order, Instant now, String kind) {
        Long facilityId = orderRepository.findFacilityId(order.getId())
                .orElseThrow(RentalOrderNotFoundException::new);
        String facilityCode = facilityAccess.code(facilityId)
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy mã cơ sở " + facilityId));
        StringBuilder suffix = new StringBuilder(CODE_RANDOM_LENGTH);
        for (int i = 0; i < CODE_RANDOM_LENGTH; i++) {
            suffix.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return "INV-" + kind + "-" + facilityCode + "-" + CODE_DATE.format(now.atZone(CODE_ZONE)) + "-" + suffix;
    }
}
