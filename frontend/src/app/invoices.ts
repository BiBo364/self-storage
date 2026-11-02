import { apiRequest } from "./api";
import { authGateway } from "./auth";
import type { InvoiceSummary } from "./types";

function accessToken(): string | undefined {
  return authGateway.getSession()?.token;
}

export function listInvoices(signal?: AbortSignal): Promise<InvoiceSummary[]> {
  return apiRequest<InvoiceSummary[]>("/api/invoices", {
    accessToken: accessToken(),
    signal,
  });
}
