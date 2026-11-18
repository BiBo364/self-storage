import { apiRequest } from "./api";
import { authGateway } from "./auth";

/** Một lịch làm việc của FS (Flow 2.1, read-only). */
export type StaffAppointment = {
  id: number;
  type: string;
  status: string;
  date: string;
  startedAt: string;
  endAt: string;
  arrivedAt: string | null;
  orderId: number | null;
  unitCode: string | null;
  facility: { id: number; name: string };
  customer: { name: string | null; email: string; phone: string | null };
  noShowDue: boolean;
  cancelReason: string | null;
};

function accessToken(): string | undefined {
  return authGateway.getSession()?.token;
}

export function fetchStaffAppointments(date: string): Promise<StaffAppointment[]> {
  return apiRequest<StaffAppointment[]>(
    `/api/staff/appointments?date=${encodeURIComponent(date)}`,
    { accessToken: accessToken() },
  );
}

export function arriveStaffAppointment(appointmentId: number): Promise<StaffAppointment> {
  return apiRequest<StaffAppointment>(`/api/staff/appointments/${appointmentId}/arrive`, {
    method: "POST",
    accessToken: accessToken(),
  });
}

export function cancelStaffAppointment(
  appointmentId: number,
  reason: string,
): Promise<StaffAppointment> {
  return apiRequest<StaffAppointment>(`/api/staff/appointments/${appointmentId}/cancel`, {
    method: "POST",
    body: { reason },
    accessToken: accessToken(),
  });
}

export type RescheduleSlot = { startAt: string; endAt: string; available: boolean };
export type RescheduleDay = { date: string; slots: RescheduleSlot[] };
/** Khung để dời lịch nhận kho: hôm nay → ngày hết hạn giữ đơn. */
export type RescheduleWindow = {
  orderId: number;
  facilityId: number;
  minDate: string;
  maxDate: string;
  days: RescheduleDay[];
};
export type RescheduledAppointment = {
  oldAppointmentId: number;
  newAppointmentId: number;
  orderId: number;
  arrived: boolean;
  date: string;
  startAt: string;
  endAt: string;
  oldTime: string;
  newTime: string;
};

export function fetchRescheduleSlots(appointmentId: number): Promise<RescheduleWindow> {
  return apiRequest<RescheduleWindow>(`/api/staff/appointments/${appointmentId}/reschedule-slots`, {
    accessToken: accessToken(),
  });
}

/** Dời lịch nhận kho — trước khi khách đến hoặc khi phiên bàn giao chưa thanh toán tháng đầu. */
export function rescheduleStaffAppointment(
  appointmentId: number,
  date: string,
  startAt: string,
  reason: string,
): Promise<RescheduledAppointment> {
  return apiRequest<RescheduledAppointment>(`/api/staff/appointments/${appointmentId}/reschedule`, {
    method: "POST",
    body: { date, startAt, reason },
    accessToken: accessToken(),
  });
}
