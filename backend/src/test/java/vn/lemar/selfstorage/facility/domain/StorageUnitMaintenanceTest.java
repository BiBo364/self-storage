package vn.lemar.selfstorage.facility.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Bảo trì sau trả kho: mất chìa → bảo trì thủ công, cron không tự mở lại. */
class StorageUnitMaintenanceTest {

    @Test
    void lostKeyReturnStartsManualMaintenanceThatCronDoesNotReopen() {
        StorageUnit unit = unit(StorageUnitStatus.RENTED);
        Instant now = Instant.now();

        assertThat(unit.startManualMaintenanceAfterReturn(now)).isTrue();
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.MAINTENANCE);
        assertThat(unit.getMaintenanceStartedAt()).isNull();
        assertThat(unit.finishMaintenanceIfDue(now.plusSeconds(86_400 * 30L))).isFalse();
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.MAINTENANCE);
    }

    @Test
    void manualMaintenanceAfterReturnOnlyFromRented() {
        StorageUnit unit = unit(StorageUnitStatus.AVAILABLE);
        assertThat(unit.startManualMaintenanceAfterReturn(Instant.now())).isFalse();
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.AVAILABLE);
    }

    private static StorageUnit unit(StorageUnitStatus status) {
        StorageUnit unit = new StorageUnit();
        ReflectionTestUtils.setField(unit, "status", status);
        return unit;
    }
}
