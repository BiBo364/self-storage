import { apiRequest } from "./api";
import { authGateway } from "./auth";

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

type ManagedFacility = {
  id: number;
};

/** Hoá đơn của cơ sở FM hiện tại; backend tự chặn nếu facility ngoài phạm vi. */
export async function loadFacilityInvoices(signal?: AbortSignal): Promise<FmInvoice[]> {
  const accessToken = authGateway.getSession()?.token;
  if (!accessToken) {
    throw new Error("Phiên đăng nhập đã hết. Vui lòng đăng nhập lại.");
  }

  const facility = await apiRequest<ManagedFacility>("/api/fm/facility", {
    accessToken,
    signal,
  });
  return apiRequest<FmInvoice[]>(`/api/fm/invoices?facilityId=${facility.id}`, {
    accessToken,
    signal,
  });
}
