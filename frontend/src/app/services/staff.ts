import { ApiError } from "../api";
import { authGateway } from "../auth";
import { authed } from "../http";

// FS — bàn giao, trả kho, hỗ trợ. Field name khớp record Java của backend.

/** `HandoverView.Contract` — handover/application/dto/HandoverView.java */
export type HandoverContract = {
  id: number;
  code: string;
  status: string;
  termsVersion: string | null;
  monthlyPrice: number;
  depositAmount: number;
  period: number;
  startDate: string | null;
  endDate: string | null;
  startDateOverrideRequested: string | null;
  startDateOverrideReason: string | null;
  startDateOverrideStatus: string | null;
  signedAt: string | null;
  documentUrl: string | null;
  pdfUrl: string | null;
};

/** `HandoverView.Invoice` */
export type HandoverInvoice = {
  id: number;
  code: string;
  status: string;
  amount: number;
  dueDate: string | null;
};

/** `HandoverView.AccessKey` */
export type HandoverAccessKey = {
  id: number;
  accessType: string;
  quantity: number | null;
  status: string;
  issuedAt: string | null;
};

/** `HandoverView` — phiên bàn giao (Flow 2.2–2.5). */
export type HandoverView = {
  id: number;
  orderId: number;
  appointmentId: number;
  result: string;
  step: string;
  unitCode: string | null;
  unitType: string | null;
  facilityId: number;
  facilityName: string | null;
  facilityAddress: string | null;
  customerId: number;
  customerName: string | null;
  customerEmail: string | null;
  customerPhone: string | null;
  appointmentDate: string | null;
  appointmentStart: string | null;
  appointmentEnd: string | null;
  arrivedAt: string | null;
  identityStatus: string;
  identityVerifiedAt: string | null;
  /** Ảnh CCCD/hộ chiếu của lần xác minh gần nhất. */
  identityPhotos: string[] | null;
  inspectionStatus: string;
  unitInspectedAt: string | null;
  inspectionNotes: string | null;
  inspectionPhotos: string[] | null;
  rejectReason: string | null;
  dueAt: string | null;
  completedAt: string | null;
  requestedAccess: string | null;
  customerConfirmedAt: string | null;
  contract: HandoverContract | null;
  firstMonthInvoice: HandoverInvoice | null;
  accessKeys: HandoverAccessKey[];
  keyAccessEnabled: boolean;
  codeAccessEnabled: boolean;
  rejectionCount: number;
  maxRejectionCount: number;
};

/** `HandoverOrderService.ContractInvoice` — hoá đơn tháng đầu trả về khi ký. */
export type ContractInvoice = {
  invoiceId: number;
  code: string;
  status: string;
  amount: number;
  dueDate: string | null;
};

/** Nhãn của `HandoverView.step` (bước kế tiếp do backend tính). */
export const HANDOVER_STEP: Record<
  string,
  { label: string; tone: "success" | "warning" | "info" | "neutral" | "danger" }
> = {
  WAITING_ARRIVAL: { label: "Chờ khách đến", tone: "neutral" },
  VERIFY_IDENTITY: { label: "Xác minh danh tính", tone: "warning" },
  INSPECTION: { label: "Kiểm tra khoang", tone: "warning" },
  OVERRIDE_PENDING: { label: "Chờ FM duyệt mốc tính tiền", tone: "info" },
  SIGN_CONTRACT: { label: "Ký hợp đồng", tone: "warning" },
  PAY_FIRST_MONTH: { label: "Chờ thanh toán tháng đầu", tone: "info" },
  CUSTOMER_CONFIRM: { label: "Chờ khách xác nhận", tone: "info" },
  HAND_OVER_KEYS: { label: "Giao chìa khoá", tone: "warning" },
  COMPLETED: { label: "Đã hoàn tất", tone: "success" },
  REJECTED: { label: "Khách từ chối khoang", tone: "danger" },
  CANCELED: { label: "Đã huỷ", tone: "neutral" },
};

export function handoverStep(step: string) {
  return HANDOVER_STEP[step] ?? { label: step, tone: "neutral" as const };
}

export function fetchStaffHandovers(): Promise<HandoverView[]> {
  return authed<HandoverView[]>("/api/staff/handovers");
}

export function fetchStaffHandover(id: number): Promise<HandoverView> {
  return authed<HandoverView>(`/api/staff/handovers/${id}`);
}

/** Đạt cần 1–4 ảnh giấy tờ đã upload qua /api/media; không đạt thì ảnh tuỳ chọn. */
export function verifyHandoverIdentity(
  id: number,
  verified: boolean,
  note: string | undefined,
  photos: string[],
): Promise<HandoverView> {
  return authed<HandoverView>(`/api/staff/handovers/${id}/identity`, {
    method: "POST",
    body: { verified, note: note || undefined, photos },
  });
}

export function saveHandoverInspection(
  id: number,
  notes: string,
  photos: string[],
): Promise<HandoverView> {
  return authed<HandoverView>(`/api/staff/handovers/${id}/inspection`, {
    method: "PUT",
    body: { notes, photos },
  });
}

/** Sửa hiện trạng sau khi khách đã đồng ý — bắt buộc lý do; chỉ khi phiên còn mở. */
export function correctHandoverInspection(
  id: number,
  notes: string,
  photos: string[],
  reason: string,
): Promise<HandoverView> {
  return authed<HandoverView>(`/api/staff/handovers/${id}/inspection-correction`, {
    method: "POST",
    body: { notes, photos, reason },
  });
}

export function markHandoverNotSigned(id: number): Promise<HandoverView> {
  return authed<HandoverView>(`/api/staff/handovers/${id}/not-signed`, { method: "POST" });
}

export function handOverKeys(id: number, quantity: number): Promise<HandoverView> {
  return authed<HandoverView>(`/api/staff/handovers/${id}/keys`, {
    method: "POST",
    body: { quantity },
  });
}

export function requestStartDateOverride(
  contractId: number,
  requestedDate: string,
  reason: string,
): Promise<null> {
  return authed<null>(`/api/staff/contracts/${contractId}/start-date-override`, {
    method: "POST",
    body: { requestedDate, reason },
  });
}

export function signContract(contractId: number, pdfUrl: string): Promise<ContractInvoice> {
  return authed<ContractInvoice>(`/api/staff/contracts/${contractId}/sign`, {
    method: "POST",
    body: { pdfUrl },
  });
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/+$/, "");

/**
 * GET /api/contracts/{id}/document — HTML để in, cần bearer token nên không mở link trực tiếp được.
 * Trả Blob URL; người gọi tự `URL.revokeObjectURL` khi không cần nữa.
 */
export async function fetchContractDocumentUrl(contractId: number): Promise<string> {
  const token = authGateway.getSession()?.token;
  const response = await fetch(`${API_BASE_URL}/api/contracts/${contractId}/document`, {
    headers: token ? { Authorization: `Bearer ${token}`, Accept: "text/html" } : undefined,
  });
  const text = await response.text();
  if (!response.ok) {
    let message = `Request failed with status ${response.status}`;
    try {
      const parsed = JSON.parse(text) as { message?: unknown };
      if (typeof parsed.message === "string") message = parsed.message;
    } catch {
      // body không phải JSON — giữ thông báo mặc định
    }
    throw new ApiError(response.status, message);
  }
  return URL.createObjectURL(new Blob([text], { type: "text/html;charset=utf-8" }));
}

/** `TenancyService.ReturnRequestView` */
export type ReturnRequestView = {
  id: number;
  contractId: number;
  unitCode: string | null;
  facilityId: number;
  preferredDate: string | null;
  note: string | null;
  status: string;
  assignedStaffId: number | null;
  appointmentId: number | null;
  appointmentStart: string | null;
  cancelReason: string | null;
  createdAt: string | null;
  /** Mốc FS xác nhận khách đến buổi trả kho; `null` = chưa đến / chưa có lịch. */
  appointmentArrivedAt: string | null;
};

export function fetchStaffReturnRequests(): Promise<ReturnRequestView[]> {
  return authed<ReturnRequestView[]>("/api/staff/return-requests");
}

/** POST /api/staff/appointments/{id}/arrive — dùng chung cho lịch RETURN. */
export function arriveAppointment(appointmentId: number): Promise<unknown> {
  return authed<unknown>(`/api/staff/appointments/${appointmentId}/arrive`, { method: "POST" });
}

/** `ContractInvoiceService.ContractBill` */
export type ContractBill = {
  invoiceId: number;
  code: string;
  type: string;
  status: string;
  title: string | null;
  amount: number;
  dueDate: string | null;
  createdAt: string | null;
};

/** `CheckoutService.CheckoutView` */
export type CheckoutView = {
  returnRequest: ReturnRequestView;
  arrivedAt: string | null;
  result: string | null;
  unitEmptied: boolean;
  cleaningRequired: boolean;
  damageAmount: number | null;
  damages: string | null;
  returnedKeys: number | null;
  photos: string[];
  note: string | null;
  inspectedAt: string | null;
  depositAmount: number | null;
  cleaningFee: number | null;
  lostKeyFee: number | null;
  keysIssued: number;
  unpaidContractInvoices: number | null;
  totalCharges: number | null;
  refundAmount: number | null;
  shortfallInvoice: ContractBill | null;
  completedAt: string | null;
};

export type CheckoutInspection = {
  unitEmptied: boolean;
  cleaningRequired: boolean;
  damageAmount?: number;
  damages?: string;
  returnedKeys?: number;
  photos?: string[];
  note?: string;
};

export function fetchCheckout(returnRequestId: number): Promise<CheckoutView> {
  return authed<CheckoutView>(`/api/staff/return-requests/${returnRequestId}/checkout`);
}

export function saveCheckoutInspection(
  returnRequestId: number,
  body: CheckoutInspection,
): Promise<CheckoutView> {
  return authed<CheckoutView>(`/api/staff/return-requests/${returnRequestId}/checkout`, {
    method: "PUT",
    body,
  });
}

export function confirmCheckout(returnRequestId: number): Promise<CheckoutView> {
  return authed<CheckoutView>(`/api/staff/return-requests/${returnRequestId}/checkout/confirm`, {
    method: "POST",
  });
}

/** `SupportService.SupportView` */
export type SupportView = {
  id: number;
  customerId: number;
  customerName: string | null;
  customerEmail: string | null;
  facilityId: number;
  facilityName: string | null;
  contractId: number | null;
  contractCode: string | null;
  unitCode: string | null;
  category: string;
  title: string;
  description: string;
  photos: string[] | null;
  status: string;
  assignedStaffId: number | null;
  assignedStaffName: string | null;
  resolution: string | null;
  feeInvoice: ContractBill | null;
  createdAt: string | null;
  assignedAt: string | null;
  resolvedAt: string | null;
};

export type SupportResolution = {
  resolution: string;
  newKeyQuantity?: number;
  feeAmount?: number;
};

export function fetchStaffSupportRequests(): Promise<SupportView[]> {
  return authed<SupportView[]>("/api/staff/support-requests");
}

export function resolveSupportRequest(id: number, body: SupportResolution): Promise<SupportView> {
  return authed<SupportView>(`/api/staff/support-requests/${id}/resolve`, {
    method: "POST",
    body,
  });
}
