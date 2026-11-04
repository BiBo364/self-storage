import { apiRequest } from "./api";
import { authGateway } from "./auth";

/** Cơ sở mà FM hiện tại phụ trách — `facilities.fm_account_id` là quan hệ 1–1. */
export type FmFacility = {
  id: number;
  code: string;
  name: string;
  address: string | null;
  operatingHours: string | null;
  status: string;
};

function accessToken(): string | undefined {
  return authGateway.getSession()?.token;
}

export function fetchCurrentFmFacility(): Promise<FmFacility> {
  return apiRequest<FmFacility>("/api/fm/facility", { accessToken: accessToken() });
}
