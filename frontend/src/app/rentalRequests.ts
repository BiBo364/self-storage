import { apiRequest } from "./api";
import type { CreateRentalRequestInput } from "./types";

export function createRentalRequest(input: CreateRentalRequestInput) {
  return apiRequest<unknown>("/api/rental-requests", {
    method: "POST",
    body: input,
  });
}
