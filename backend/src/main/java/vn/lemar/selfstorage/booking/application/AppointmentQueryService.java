package vn.lemar.selfstorage.booking.application;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.dto.FmAppointmentResponse;
import vn.lemar.selfstorage.booking.application.dto.FmFacilityStaffResponse;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.FacilityStaffQuery;

/** Màn lịch hẹn của FM (spec 1.5): xem lịch theo ngày, xem FS của cơ sở để phân công. */
@Service
public class AppointmentQueryService {

    static final String PERMISSION_READ = "appointment.read";
    static final String PERMISSION_ASSIGN = "appointment.assign";

    private final AppointmentReadRepository readRepository;
    private final FacilityStaffQuery facilityStaffQuery;
    private final Access access;

    AppointmentQueryService(AppointmentReadRepository readRepository, FacilityStaffQuery facilityStaffQuery,
                            Access access) {
        this.readRepository = readRepository;
        this.facilityStaffQuery = facilityStaffQuery;
        this.access = access;
    }

    /** Lịch check-in đang Pending của cơ sở trong ngày — quyền {@code appointment.read} theo cơ sở. */
    @Transactional(readOnly = true)
    public List<FmAppointmentResponse> listForFacility(Long facilityId, LocalDate date) {
        access.can(PERMISSION_READ, facilityId);
        return readRepository.findByFacilityAndDate(facilityId, date).stream()
                .map(FmAppointmentResponse::from).toList();
    }

    /** FS đang hoạt động của cơ sở để FM chọn — cùng quyền với thao tác phân công. */
    @Transactional(readOnly = true)
    public List<FmFacilityStaffResponse> staffOfFacility(Long facilityId) {
        access.can(PERMISSION_ASSIGN, facilityId);
        return facilityStaffQuery.listStaff(facilityId).stream()
                .map(staff -> new FmFacilityStaffResponse(staff.id(), staff.name(), staff.email(),
                        staff.phone(), staff.assignedAt())).toList();
    }
}
