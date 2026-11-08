import { apiRequest } from "./api";
import { authGateway } from "./auth";

export type PendingPaymentCase = {
  caseId: number;
  transactionId: number;
  invoiceId: number;
  vnpTxnRef: string;
  amount: number;
  transactionCreatedAt: string;
  detectedAt: string;
  notifiedAt: string | null;
  pendingAgeMinutes: number;
  status: "Open" | "Resolved";
  resolution: "Success" | "Failed" | "Duplicate" | null;
  gatewayTransactionNo: string | null;
  paidAt: string | null;
  note: string | null;
  evidenceUrl: string | null;
  resolvedAt: string | null;
  resolvedByEmail: string | null;
};

export type PendingPaymentPage = {
  items: PendingPaymentCase[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type RefundCase = {
  invoiceId: number;
  invoiceCode: string;
  orderId: number | null;
  amount: number;
  refundStatus: "Required" | "Processing" | "Refunded";
  refundReason: string | null;
  refundRequestedAt: string | null;
  vnpTxnRef: string | null;
  gatewayTransactionNo: string | null;
  paidAt: string | null;
};

export type RefundCasePage = {
  items: RefundCase[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type ReconcileOutcome = "SUCCESS" | "FAILED";

export type ReconcilePayload = {
  outcome: ReconcileOutcome;
  note: string;
  paidAt: string | null;
  gatewayTransactionNo: string | null;
  evidenceUrl: string | null;
};

function token() {
  return authGateway.getSession()?.token;
}

export type ReconciliationView = "open" | "history";

export function loadPendingCases(
  page: number,
  size = 20,
  view: ReconciliationView = "open",
): Promise<PendingPaymentPage> {
  const params = new URLSearchParams({
    view,
    olderThanMinutes: "15",
    page: String(page),
    size: String(size),
  });
  return apiRequest<PendingPaymentPage>(`/api/admin/payments/reconciliation?${params.toString()}`, {
    accessToken: token(),
  });
}

export function reconcilePayment(txnRef: string, payload: ReconcilePayload): Promise<unknown> {
  return apiRequest(`/api/admin/payments/${encodeURIComponent(txnRef)}/reconcile`, {
    method: "POST",
    accessToken: token(),
    body: payload,
  });
}
