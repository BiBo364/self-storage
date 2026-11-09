import { authed, query } from "../http";

/*
 * Nhân sự (ca làm việc, nghỉ phép, hồ sơ, báo cáo nhân sự) — dùng chung FM / FS / BOM / ADMIN. Field name khớp đúng
 * record Java (staffing/application/*, backoffice/repository/BackofficeReadRepository.StaffReportRow). LocalDate là
 * `yyyy-MM-dd`, giờ ca là `HH:mm` (giờ VN), Instant là chuỗi ISO.
 */

// ------------------------------------------------------------------ Ca làm việc

/** `StaffShiftService.ShiftView` */
export type StaffShift = {
  id: number;
  facilityId: number;
  facilityCode: string;
  staffId: number;
  staffName: string | null;
  staffEmail: string;
  shiftDate: string;
  startTime: string;
  endTime: string;
  note: string | null;
  updatedAt: string;
};

export type ShiftInput = {
  shiftDate: string;
  startTime: string;
  endTime: string;
  note?: string | null;
};

export function fetchFacilityShifts(
  facilityId: number,
  from: string,
  to: string,
): Promise<StaffShift[]> {
  return authed<StaffShift[]>(`/api/fm/staff-shifts${query({ facilityId, from, to })}`);
}

export function createShift(
  body: ShiftInput & { facilityId: number; staffId: number },
): Promise<StaffShift> {
  return authed<StaffShift>("/api/fm/staff-shifts", { method: "POST", body });
}

export function updateShift(id: number, body: ShiftInput): Promise<StaffShift> {
  return authed<StaffShift>(`/api/fm/staff-shifts/${id}`, { method: "PUT", body });
}

export function deleteShift(id: number): Promise<null> {
  return authed<null>(`/api/fm/staff-shifts/${id}`, { method: "DELETE" });
}

export function fetchMyShifts(from: string, to: string): Promise<StaffShift[]> {
  return authed<StaffShift[]>(`/api/staff/shifts${query({ from, to })}`);
}

// ------------------------------------------------------------------ Nghỉ phép

/** `StaffLeaveService.LeaveView` — `cancelable` do backend tính (FS còn huỷ được). */
export type StaffLeave = {
  id: number;
  staffId: number;
  staffName: string | null;
  staffEmail: string;
  fromDate: string;
  toDate: string;
  reason: string;
  status: string;
  reviewedBy: number | null;
  reviewedByEmail: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  createdAt: string;
  cancelable: boolean;
};

export const LEAVE_STATUS_LABEL: Record<string, string> = {
  Pending: "Chờ duyệt",
  Approved: "Đã duyệt",
  Rejected: "Bị từ chối",
  Canceled: "Đã huỷ",
};

export function fetchMyLeaves(): Promise<StaffLeave[]> {
  return authed<StaffLeave[]>("/api/staff/leaves");
}

export function requestLeave(body: {
  fromDate: string;
  toDate: string;
  reason: string;
}): Promise<StaffLeave> {
  return authed<StaffLeave>("/api/staff/leaves", { method: "POST", body });
}

export function cancelLeave(id: number): Promise<StaffLeave> {
  return authed<StaffLeave>(`/api/staff/leaves/${id}/cancel`, { method: "POST" });
}

export function fetchFacilityLeaves(facilityId: number, status?: string): Promise<StaffLeave[]> {
  return authed<StaffLeave[]>(`/api/fm/staff-leaves${query({ facilityId, status })}`);
}

export function approveLeave(id: number, note: string): Promise<StaffLeave> {
  return authed<StaffLeave>(`/api/fm/staff-leaves/${id}/approve`, {
    method: "POST",
    body: { note },
  });
}

export function rejectLeave(id: number, note: string): Promise<StaffLeave> {
  return authed<StaffLeave>(`/api/fm/staff-leaves/${id}/reject`, {
    method: "POST",
    body: { note },
  });
}

// ------------------------------------------------------------------ Hồ sơ nhân viên

/** `StaffProfileService.ProfileSummary` — bản FM / FS đọc, không có số giấy tờ. */
export type StaffProfileSummary = {
  accountId: number;
  email: string;
  name: string | null;
  phone: string | null;
  role: string;
  status: string;
  position: string | null;
  hireDate: string | null;
  emergencyContactName: string | null;
  emergencyContactPhone: string | null;
  note: string | null;
  updatedAt: string | null;
};

/** `StaffProfileService.ProfileView` — bản ADMIN / BOM, có `idNumber`. */
export type StaffProfile = StaffProfileSummary & { idNumber: string | null };

/** Body `PUT …/profile` — thay toàn bộ, trường rỗng = xoá. */
export type StaffProfileInput = {
  position: string | null;
  hireDate: string | null;
  idNumber: string | null;
  emergencyContactName: string | null;
  emergencyContactPhone: string | null;
  note: string | null;
};

export function fetchAdminStaffProfile(accountId: number): Promise<StaffProfile> {
  return authed<StaffProfile>(`/api/admin/accounts/${accountId}/profile`);
}

export function updateAdminStaffProfile(
  accountId: number,
  body: StaffProfileInput,
): Promise<StaffProfile> {
  return authed<StaffProfile>(`/api/admin/accounts/${accountId}/profile`, { method: "PUT", body });
}

export function fetchBomStaffProfile(accountId: number): Promise<StaffProfile> {
  return authed<StaffProfile>(`/api/bom/staff/${accountId}/profile`);
}

export function updateBomStaffProfile(
  accountId: number,
  body: StaffProfileInput,
): Promise<StaffProfile> {
  return authed<StaffProfile>(`/api/bom/staff/${accountId}/profile`, { method: "PUT", body });
}

export function fetchFmStaffProfile(accountId: number): Promise<StaffProfileSummary> {
  return authed<StaffProfileSummary>(`/api/fm/staff/${accountId}/profile`);
}

export function fetchMyProfile(): Promise<StaffProfileSummary> {
  return authed<StaffProfileSummary>("/api/staff/profile");
}

// ------------------------------------------------------------------ Báo cáo nhân sự

/** `BackofficeReadRepository.StaffReportRow` */
export type StaffReportRow = {
  staffId: number;
  name: string | null;
  email: string;
  status: string;
  completedCheckins: number;
  completedReturns: number;
  handoversCompleted: number;
  checkoutsCompleted: number;
  supportResolved: number;
  shiftHours: number;
  leaveDays: number;
};

/** `BackofficeService.StaffReport` */
export type StaffReport = {
  from: string;
  to: string;
  facilityId: number | null;
  rows: StaffReportRow[];
};

export function fetchFmStaffReport(params: {
  facilityId: number;
  from?: string;
  to?: string;
}): Promise<StaffReport> {
  return authed<StaffReport>(`/api/fm/reports/staff${query(params)}`);
}

export function fetchBomStaffReport(params: {
  facilityId?: number | null;
  from?: string;
  to?: string;
}): Promise<StaffReport> {
  return authed<StaffReport>(`/api/bom/reports/staff${query(params)}`);
}

// ------------------------------------------------------------------ Ngày (hiển thị)

function pad(value: number) {
  return String(value).padStart(2, "0");
}

/** `yyyy-MM-dd` của một Date theo giờ máy (giờ VN khi chạy ở VN). */
export function isoDate(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function addDays(iso: string, days: number): string {
  const [year, month, day] = iso.split("-").map(Number);
  return isoDate(new Date(year, month - 1, day + days));
}

/** Thứ Hai của tuần chứa ngày `iso`. */
export function mondayOf(iso: string): string {
  const [year, month, day] = iso.split("-").map(Number);
  const date = new Date(year, month - 1, day);
  const offset = (date.getDay() + 6) % 7;
  return addDays(iso, -offset);
}

export function formatDmy(iso: string | null): string {
  if (!iso) return "—";
  const [year, month, day] = iso.slice(0, 10).split("-");
  return `${day}/${month}/${year}`;
}

const WEEKDAY = ["CN", "T2", "T3", "T4", "T5", "T6", "T7"];

export function weekdayLabel(iso: string): string {
  const [year, month, day] = iso.split("-").map(Number);
  return WEEKDAY[new Date(year, month - 1, day).getDay()];
}
