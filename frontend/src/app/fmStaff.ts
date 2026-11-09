import { apiRequest } from "./api";
import { authGateway } from "./auth";
import { query } from "./http";

/** `StaffAvailability.ShiftTime` — giờ `HH:mm` (giờ VN). */
export type ShiftTime = { startTime: string; endTime: string };

/**
 * Một FS đang hoạt động của cơ sở — `booking/application/dto/FmFacilityStaffResponse`. Ca / nghỉ phép / việc còn mở
 * tính cho ngày `date` (mặc định hôm nay).
 */
export type FmStaff = {
  id: number;
  name: string | null;
  email: string;
  phone: string | null;
  assignedAt: string;
  date: string;
  shift: ShiftTime | null;
  shifts: ShiftTime[];
  onLeave: boolean;
  openWork: { checkins: number; returns: number };
  openSupport: number;
};

function accessToken(): string | undefined {
  return authGateway.getSession()?.token;
}

export function fetchFacilityStaff(facilityId: number, date?: string): Promise<FmStaff[]> {
  return apiRequest<FmStaff[]>(`/api/fm/facilities/${facilityId}/staff${query({ date })}`, {
    accessToken: accessToken(),
  });
}

/** Tóm tắt ngày của FS cho màn phân công — chỉ hiển thị, luật phân công do backend kiểm. */
export function staffDaySummary(staff: FmStaff): string {
  if (staff.onLeave) return "Nghỉ phép";
  const shifts =
    staff.shifts.length === 0
      ? "Không có ca"
      : `Ca ${staff.shifts.map((s) => `${s.startTime}–${s.endTime}`).join(", ")}`;
  const work = [
    staff.openWork.checkins > 0 ? `${staff.openWork.checkins} check-in` : null,
    staff.openWork.returns > 0 ? `${staff.openWork.returns} trả kho` : null,
    staff.openSupport > 0 ? `${staff.openSupport} hỗ trợ` : null,
  ].filter(Boolean);
  return work.length > 0 ? `${shifts} · ${work.join(", ")}` : shifts;
}
