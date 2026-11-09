package vn.lemar.selfstorage.facility.application;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.facility.repository.FacilityAccessRepository;

/** Public API for checking facility ownership by its assigned facility manager. */
@Service
public class FacilityAccess {

    private final FacilityAccessRepository facilityAccessRepository;

    public FacilityAccess(FacilityAccessRepository facilityAccessRepository) {
        this.facilityAccessRepository = facilityAccessRepository;
    }

    @Transactional(readOnly = true)
    public boolean isManagedBy(Long accountId, Long facilityId) {
        return facilityAccessRepository.isManagedBy(accountId, facilityId);
    }

    /** Cơ sở ngừng hoạt động — không nhận yêu cầu thuê mới (catalog public cũng ẩn cơ sở này). */
    @Transactional(readOnly = true)
    public boolean isInactive(Long facilityId) {
        return facilityAccessRepository.isInactive(facilityId);
    }

    @Transactional(readOnly = true)
    public boolean exists(Long facilityId) {
        return facilityAccessRepository.exists(facilityId);
    }

    /** Mã cơ sở ({@code facilities.code}, vd {@code Q7}) — dùng trong mã hoá đơn. */
    @Transactional(readOnly = true)
    public Optional<String> code(Long facilityId) {
        return facilityAccessRepository.findCode(facilityId);
    }

    /** Loại quyền truy cập cơ sở đang bật; rỗng nếu cơ sở không tồn tại. */
    @Transactional(readOnly = true)
    public Optional<FacilityAccessOptions> accessOptions(Long facilityId) {
        return facilityAccessRepository.findAccessOptions(facilityId);
    }

    /** FM phụ trách cơ sở ({@code facilities.fm_account_id}); rỗng nếu chưa gán FM. */
    @Transactional(readOnly = true)
    public Optional<Long> managerAccountId(Long facilityId) {
        return facilityAccessRepository.findManagerAccountId(facilityId);
    }
}
