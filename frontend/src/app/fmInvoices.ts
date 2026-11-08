import { apiRequest } from "./api";
import { authGateway } from "./auth";
import type { RefundCasePage } from "./adminReconciliation";

export type FmInvoiceType =
  "Deposit" | "DepositSupplement" | "Rental" | "Extension" | "Penalty" | "Service";

export type FmInvoiceStatus = "Unpaid" | "Paid" | "Canceled" | "Expired";

export type FmPaymentStatus = "Pending" | "Success" | "Failed" | "Duplicate";

export type FmInvoice = {
  id: number;
  orderId: number | null;
  code: string;
  title: string;
  description: string | null;
  type: FmInvoiceType;
  status: FmInvoiceStatus;
  amount: number;
  createdAt: string;
  dueDate: string | null;
  unitId: number | null;
  unitCode: string | null;
  facilityId: number;
  facilityName: string;
  customerId: number;
  customerName: string | null;
  customerEmail: string;
  paymentStatus: FmPaymentStatus | null;
  vnpTxnRef: string | null;
  paymentCreatedAt: string | null;
  paidAt: string | null;
  pendingAgeMinutes: number;
  needsReconciliation: boolean;
  duplicateRefundRequired: boolean;
};

export type FmInvoicePage = {
  items: FmInvoice[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type FmInvoiceFilters = {
  status?: FmInvoiceStatus;
  type?: FmInvoiceType;
  paymentStatus?: FmPaymentStatus;
  olderThanMinutes?: number;
  page: number;
  size: number;
};

type ManagedFacility = {
  id: number;
};

/** Một trang hoá đơn của cơ sở FM hiện tại; backend tự chặn nếu facility ngoài phạm vi. */
export async function loadFacilityInvoices(
  filters: FmInvoiceFilters,
  signal?: AbortSignal,
): Promise<FmInvoicePage> {
  const accessToken = authGateway.getSession()?.token;
  if (!accessToken) {
    throw new Error("Phiên đăng nhập đã hết. Vui lòng đăng nhập lại.");
  }

  const facility = await apiRequest<ManagedFacility>("/api/fm/facility", {
    accessToken,
    signal,
  });
  const params = new URLSearchParams({
    facilityId: String(facility.id),
    page: String(filters.page),
    size: String(filters.size),
  });
  if (filters.status) params.set("status", filters.status);
  if (filters.type) params.set("type", filters.type);
  if (filters.paymentStatus) params.set("paymentStatus", filters.paymentStatus);
  if (filters.olderThanMinutes !== undefined) {
    params.set("olderThanMinutes", String(filters.olderThanMinutes));
  }

  return apiRequest<FmInvoicePage>(`/api/fm/invoices?${params.toString()}`, {
    accessToken,
    signal,
  });
}

/** Queue refund Required theo facility của FM hiện tại. */
export async function loadFmRefundCases(page: number, size = 20): Promise<RefundCasePage> {
  const accessToken = authGateway.getSession()?.token;
  if (!accessToken) {
    throw new Error("Phiên đăng nhập đã hết. Vui lòng đăng nhập lại.");
  }
  const facility = await apiRequest<ManagedFacility>("/api/fm/facility", { accessToken });
  const params = new URLSearchParams({
    facilityId: String(facility.id),
    page: String(page),
    size: String(size),
  });
  return apiRequest<RefundCasePage>(`/api/fm/invoices/refunds?${params.toString()}`, {
    accessToken,
  });
}
