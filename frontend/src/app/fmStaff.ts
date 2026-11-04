import { apiRequest } from "./api";
import { authGateway } from "./auth";

/** Một FS đang hoạt động của cơ sở. */
export type FmStaff = {
  id: number;
  name: string | null;
  email: string;
  phone: string | null;
  assignedAt: string;
};

function accessToken(): string | undefined {
  return authGateway.getSession()?.token;
}

export function fetchFacilityStaff(facilityId: number): Promise<FmStaff[]> {
  return apiRequest<FmStaff[]>(`/api/fm/facilities/${facilityId}/staff`, {
    accessToken: accessToken(),
  });
}
