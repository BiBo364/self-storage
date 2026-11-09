package vn.lemar.selfstorage.booking.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.dto.FmAppointmentResponse;
import vn.lemar.selfstorage.booking.application.dto.FmFacilityStaffResponse;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.FacilityStaffQuery;
import vn.lemar.selfstorage.staffing.application.StaffAvailability;
import vn.lemar.selfstorage.staffing.application.StaffAvailability.StaffDay;

/** Màn lịch hẹn của FM (spec 1.5): xem lịch theo ngày, xem FS của cơ sở để phân công. */
@Service
public class AppointmentQueryService {

    static final String PERMISSION_READ = "appointment.read";
    static final String PERMISSION_ASSIGN = "appointment.assign";
    static final String PERMISSION_CHECKIN = "appointment.checkin";
    private static final ZoneId FACILITY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AppointmentReadRepository readRepository;
    private final FacilityStaffQuery facilityStaffQuery;
    private final Access access;
    private final StaffAvailability staffAvailability;

    AppointmentQueryService(AppointmentReadRepository readRepository, FacilityStaffQuery facilityStaffQuery,
                            Access access, StaffAvailability staffAvailability) {
        this.readRepository = readRepository;
        this.facilityStaffQuery = facilityStaffQuery;
        this.access = access;
        this.staffAvailability = staffAvailability;
    }

    /** Lịch check-in đang Pending của cơ sở trong ngày — quyền {@code appointment.read} theo cơ sở. */
    @Transactional(readOnly = true)
    public List<FmAppointmentResponse> listForFacility(Long facilityId, LocalDate date) {
        access.can(PERMISSION_READ, facilityId);
        return readRepository.findByFacilityAndDate(facilityId, date).stream()
                .map(FmAppointmentResponse::from).toList();
    }

    /** FS đang hoạt động của cơ sở kèm ca / nghỉ phép / việc còn mở của hôm nay. */
    @Transactional(readOnly = true)
    public List<FmFacilityStaffResponse> staffOfFacility(Long facilityId) {
        return staffOfFacility(facilityId, null);
    }

    /**
     * FS đang hoạt động của cơ sở để FM chọn — cùng quyền với thao tác phân công. Mỗi FS kèm ca tại cơ sở, nghỉ phép
     * và việc còn mở của ngày {@code date} ({@code null} = hôm nay, giờ VN).
     */
    @Transactional(readOnly = true)
    public List<FmFacilityStaffResponse> staffOfFacility(Long facilityId, LocalDate date) {
        access.can(PERMISSION_ASSIGN, facilityId);
        LocalDate day = date == null ? LocalDate.now(FACILITY_ZONE) : date;
        return facilityStaffQuery.listStaff(facilityId).stream().map(staff -> {
            StaffDay status = staffAvailability.day(staff.id(), facilityId, day);
            return new FmFacilityStaffResponse(staff.id(), staff.name(), staff.email(), staff.phone(),
                    staff.assignedAt(), day, status.shift(), status.shifts(), status.onLeave(), status.openWork(),
                    status.openSupport());
        }).toList();
    }
}
