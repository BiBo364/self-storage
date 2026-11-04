package vn.lemar.selfstorage.facility.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.facility.repository.StorageUnitReadRepository;

/** Public API đọc danh sách khoang của một cơ sở cho màn FM. */
@Service
public class StorageUnitListQuery {

    private final StorageUnitReadRepository readRepository;

    public StorageUnitListQuery(StorageUnitReadRepository readRepository) {
        this.readRepository = readRepository;
    }

    /**
     * @param status PascalCase như DB ({@code Available}, ...); {@code null} = mọi trạng thái
     */
    @Transactional(readOnly = true)
    public List<StorageUnitListView> listByFacility(Long facilityId, String status, Long unitTypeId) {
        return readRepository.findByFacility(facilityId, status, unitTypeId);
    }
}
