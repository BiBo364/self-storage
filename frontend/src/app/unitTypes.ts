import { apiRequest } from "./api";
import type { UnitTypeSummary } from "./types";

export function listUnitTypes(facilityId: number, signal?: AbortSignal) {
  return apiRequest<UnitTypeSummary[]>(`/api/unit-types?facilityId=${facilityId}`, { signal });
}
