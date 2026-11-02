import { apiRequest } from "./api";
import { authGateway } from "./auth";
import type {
  ApproveRentalRequestResult,
  ManagedFacility,
  RejectRentalRequestResult,
  RejectReason,
  RentalRequestPage,
  StorageUnitSummary,
} from "./types";

function accessToken(): string | undefined {
  return authGateway.getSession()?.token;
}

export function fetchManagedFacility(signal?: AbortSignal): Promise<ManagedFacility> {
  return apiRequest<ManagedFacility>("/api/fm/facility", {
    accessToken: accessToken(),
    signal,
  });
}

export function fetchRentalRequestQueue(
  facilityId: number,
  signal?: AbortSignal,
): Promise<RentalRequestPage> {
  return apiRequest<RentalRequestPage>(
    `/api/fm/rental-requests?facilityId=${facilityId}&page=0&size=100`,
    {
      accessToken: accessToken(),
      signal,
    },
  );
}

export function fetchFacilityUnits(
  facilityId: number,
  signal?: AbortSignal,
): Promise<StorageUnitSummary[]> {
  return apiRequest<StorageUnitSummary[]>(`/api/fm/storage-units?facilityId=${facilityId}`, {
    accessToken: accessToken(),
    signal,
  });
}

export function approveRentalRequest(
  requestId: number,
  unitId: number,
): Promise<ApproveRentalRequestResult> {
  return apiRequest<ApproveRentalRequestResult>(`/api/fm/rental-requests/${requestId}/approve`, {
    method: "POST",
    body: { unitId },
    accessToken: accessToken(),
  });
}

export function rejectRentalRequest(
  requestId: number,
  rejectReason: RejectReason,
  rejectNote?: string,
): Promise<RejectRentalRequestResult> {
  return apiRequest<RejectRentalRequestResult>(`/api/fm/rental-requests/${requestId}/reject`, {
    method: "POST",
    body: { rejectReason, rejectNote: rejectNote || undefined },
    accessToken: accessToken(),
  });
}
