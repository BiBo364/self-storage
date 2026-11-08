import { apiRequest } from "./api";
import { authGateway } from "./auth";

/** Một lịch check-in trên màn phân công FS của FM. */
export type FmAppointment = {
  id: number;
  type: string;
  status: string;
  date: string;
  startedAt: string | null;
  endAt: string | null;
  orderId: number | null;
  orderStatus: string | null;
  unitCode: string | null;
  customerName: string | null;
  customerEmail: string;
  staffId: number | null;
  staffEmail: string | null;
  attempt: number;
  rebooked: boolean;
  rebookReason: string | null;
  previousCancelReason: string | null;
};

function accessToken(): string | undefined {
  return authGateway.getSession()?.token;
}

export function fetchFmAppointments(facilityId: number, date: string): Promise<FmAppointment[]> {
  const query = new URLSearchParams({ facilityId: String(facilityId), date });
  return apiRequest<FmAppointment[]>(`/api/fm/appointments?${query.toString()}`, {
    accessToken: accessToken(),
  });
}

export function assignAppointmentStaff(
  appointmentId: number,
  staffId: number,
): Promise<FmAppointment> {
  return apiRequest<FmAppointment>(`/api/fm/appointments/${appointmentId}/staff`, {
    method: "POST",
    body: { staffId },
    accessToken: accessToken(),
  });
}
