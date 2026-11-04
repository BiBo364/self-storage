package vn.lemar.selfstorage.booking.domain;

import java.time.Instant;

/** Guard no-show dùng chung: lịch check-in Pending đã quá end_at. */
public final class AppointmentNoShowPolicy {

    private AppointmentNoShowPolicy() {
    }

    public static boolean isDue(String type, String status, Instant endAt, Instant now) {
        return AppointmentType.CHECKIN.name().equals(type)
                && AppointmentStatus.Pending.name().equals(status)
                && endAt.isBefore(now);
    }

    public static boolean isDue(AppointmentType type, AppointmentStatus status, Instant endAt, Instant now) {
        return type == AppointmentType.CHECKIN && status == AppointmentStatus.Pending
                && endAt.isBefore(now);
    }
}
