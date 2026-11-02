import { apiRequest } from "./api";
import type { FacilitySummary } from "./types";

export function listFacilities(signal?: AbortSignal) {
  return apiRequest<FacilitySummary[]>("/api/facilities", { signal });
}
