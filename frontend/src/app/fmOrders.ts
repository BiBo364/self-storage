import { apiRequest } from "./api";
import { authGateway } from "./auth";
import type { FmRentalOrderPage } from "./types";

function accessToken(): string | undefined {
  return authGateway.getSession()?.token;
}

export function fetchFmRentalOrders(
  facilityId: number,
  status?: string,
  page = 0,
  size = 20,
): Promise<FmRentalOrderPage> {
  const query = new URLSearchParams({
    facilityId: String(facilityId),
    page: String(page),
    size: String(size),
  });
  if (status) query.set("status", status);
  return apiRequest<FmRentalOrderPage>(`/api/fm/rental-orders?${query.toString()}`, {
    accessToken: accessToken(),
  });
}
