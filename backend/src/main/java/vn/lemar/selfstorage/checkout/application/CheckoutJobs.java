package vn.lemar.selfstorage.checkout.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.ContractInvoicePaid;
import vn.lemar.selfstorage.facility.application.StorageUnitService;
import vn.lemar.selfstorage.pricing.application.PricingQuery;

/**
 * Điểm vào bất đồng bộ của trả kho: hoá đơn phần còn thiếu {@code Paid} → hoàn tất; cron mở lại khoang hết
 * {@code unit.maintenance_days} bảo trì sau trả kho (mỗi khoang một transaction).
 */
@Component
public class CheckoutJobs {

    static final String POLICY_MAINTENANCE_DAYS = "unit.maintenance_days";
    private static final Logger LOG = LoggerFactory.getLogger(CheckoutJobs.class);

    private final CheckoutService checkoutService;
    private final StorageUnitService storageUnits;
    private final PricingQuery pricingQuery;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate transaction;

    CheckoutJobs(CheckoutService checkoutService, StorageUnitService storageUnits, PricingQuery pricingQuery,
                 AuditRecorder auditRecorder, PlatformTransactionManager transactionManager) {
        this.checkoutService = checkoutService;
        this.storageUnits = storageUnits;
        this.pricingQuery = pricingQuery;
        this.auditRecorder = auditRecorder;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @EventListener
    public void on(ContractInvoicePaid event) {
        if ("Penalty".equals(event.invoiceType())) {
            checkoutService.onShortfallPaid(event.invoiceId());
        }
    }

    @Scheduled(
            fixedDelayString = "${checkout.maintenance.fixed-delay-ms:600000}",
            initialDelayString = "${checkout.maintenance.initial-delay-ms:120000}")
    public void reopenUnits() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(pricingQuery.intPolicy(POLICY_MAINTENANCE_DAYS)));
        int done = 0;
        for (Long unitId : storageUnits.maintenanceDueIds(cutoff)) {
            try {
                Boolean changed = transaction.execute(status -> reopen(unitId, cutoff));
                if (Boolean.TRUE.equals(changed)) {
                    done++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Cron mở lại khoang {} lỗi: {}", unitId, e.getMessage());
            }
        }
        if (done > 0) {
            LOG.info("Cron mở lại khoang sau bảo trì: {}", done);
        }
    }

    boolean reopen(Long unitId, Instant cutoff) {
        if (!storageUnits.finishMaintenanceIfDue(unitId, cutoff)) {
            return false;
        }
        auditRecorder.record(new AuditEntry(AuditAction.STORAGE_UNIT_AVAILABLE, AuditEntityType.STORAGE_UNIT,
                String.valueOf(unitId), Map.of("status", "Maintenance"),
                Map.of("status", "Available", "reason", "Hết thời gian bảo trì sau trả kho"), null, null));
        return true;
    }
}
