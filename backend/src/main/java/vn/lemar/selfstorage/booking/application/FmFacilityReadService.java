package vn.lemar.selfstorage.booking.application;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.exception.FacilityNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.StorageUnitFilterException;
import vn.lemar.selfstorage.facility.application.FacilityCatalogQuery;
import vn.lemar.selfstorage.facility.application.ManagedFacilityView;
import vn.lemar.selfstorage.facility.application.StorageUnitListView;
import vn.lemar.selfstorage.facility.application.StorageUnitListQuery;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/**
 * Đọc cơ sở và khoang cho màn FM. Controller nằm ở booking vì module facility không được phụ thuộc
 * identity; quyền vẫn đi qua {@link Access} như các màn FM khác.
 */
@Service
public class FmFacilityReadService {

    private static final Set<String> UNIT_STATUSES =
            Set.of("Available", "Reserved", "Rented", "Maintenance");

    private final FacilityCatalogQuery facilityCatalogQuery;
    private final StorageUnitListQuery storageUnitListQuery;
    private final Access access;
    private final CurrentAccountProvider currentAccount;

    public FmFacilityReadService(FacilityCatalogQuery facilityCatalogQuery,
                                 StorageUnitListQuery storageUnitListQuery, Access access,
                                 CurrentAccountProvider currentAccount) {
        this.facilityCatalogQuery = facilityCatalogQuery;
        this.storageUnitListQuery = storageUnitListQuery;
        this.access = access;
        this.currentAccount = currentAccount;
    }

    /** Cơ sở FM đang phụ trách; chưa được gán → 404. */
    @Transactional(readOnly = true)
    public ManagedFacilityView currentFacility() {
        return facilityCatalogQuery.findManagedBy(currentAccount.getCurrentAccountId())
                .orElseThrow(() -> new FacilityNotFoundException("Tài khoản FM chưa được gán cơ sở"));
    }

    /** Khoang của cơ sở trong phạm vi FM; {@code status} PascalCase như DB. */
    @Transactional(readOnly = true)
    public List<StorageUnitListView> storageUnits(Long facilityId, String status, Long unitTypeId) {
        access.can("facility.access", facilityId);
        return storageUnitListQuery.listByFacility(facilityId, normalizeStatus(status), unitTypeId);
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.strip();
        if (!UNIT_STATUSES.contains(normalized)) {
            throw new StorageUnitFilterException("Trạng thái khoang không hợp lệ: " + status);
        }
        return normalized;
    }
}
