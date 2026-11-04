package vn.lemar.selfstorage.booking.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.dto.StaffAppointmentResponse;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository.AppointmentRow;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/**
 * Lịch làm việc của FS đang đăng nhập (C1 read-only): chỉ lịch có {@code staff_id} = FS hiện tại,
 * kiểm {@code appointment.read} theo cơ sở của từng lịch.
 */
@Service
public class StaffAppointmentQueryService {

    private static final ZoneId FACILITY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AppointmentReadRepository readRepository;
    private final CurrentAccountProvider currentAccount;
    private final Access access;

    StaffAppointmentQueryService(AppointmentReadRepository readRepository, CurrentAccountProvider currentAccount,
                                 Access access) {
        this.readRepository = readRepository;
        this.currentAccount = currentAccount;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<StaffAppointmentResponse> listForCurrentStaff(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(FACILITY_ZONE) : date;
        List<AppointmentRow> rows = readRepository.findByStaffAndDate(
                currentAccount.getCurrentAccountId(), day);
        Set<Long> checkedFacilities = new HashSet<>();
        for (AppointmentRow row : rows) {
            if (checkedFacilities.add(row.facilityId())) {
                access.can(AppointmentQueryService.PERMISSION_READ, row.facilityId());
            }
        }
        return rows.stream().map(StaffAppointmentResponse::from).toList();
    }
}
