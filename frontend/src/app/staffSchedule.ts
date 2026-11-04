import { apiRequest } from "./api";
import { authGateway } from "./auth";

/** Một lịch làm việc của FS (Flow 2.1, read-only). */
export type StaffAppointment = {
  id: number;
  type: string;
  status: string;
  date: string;
  startedAt: string | null;
  endAt: string | null;
  arrivedAt: string | null;
  orderId: number | null;
  unitCode: string | null;
  facility: { id: number; name: string };
  customer: { name: string | null; email: string; phone: string | null };
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
