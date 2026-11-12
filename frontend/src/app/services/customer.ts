import { useEffect, useState } from "react";
import { ApiError, apiRequest } from "../api";
import { authGateway } from "../auth";
import { authed, errorMessage, errorStatus, query } from "../http";

// ---------------------------------------------------------------- Catalog (public)

/** GET /api/facilities — một cơ sở `Active` (FacilityCatalogView). */
export type CatalogFacility = {
  id: number;
  code: string;
  name: string;
  address: string | null;
  operatingHours: string | null;
  unitTypeIds: number[];
  /** Ghim bản đồ — cùng `null` khi cơ sở chưa ghim. */
  latitude: number | null;
  longitude: number | null;
  floorCount: number;
};

/** GET /api/unit-types?facilityId= — UnitTypeQueryService.UnitTypeOption. */
export type UnitTypeOption = {
  id: number;
  name: string;
  width: number | null;
  depth: number | null;
  height: number | null;
  area: number | null;
  monthlyPrice: number | null;
};

export type Catalog = {
  facilities: CatalogFacility[];
  /** Mọi loại khoang mà ít nhất một cơ sở cung cấp, sắp theo id. */
  unitTypes: UnitTypeOption[];
  /** facilityId → loại khoang cơ sở đó cung cấp. */
  offerings: Record<number, UnitTypeOption[]>;
};

export function fetchFacilities(): Promise<CatalogFacility[]> {
  return apiRequest<CatalogFacility[]>("/api/facilities");
}

export function fetchUnitTypes(facilityId: number): Promise<UnitTypeOption[]> {
  return apiRequest<UnitTypeOption[]>(`/api/unit-types${query({ facilityId })}`);
}

/** Catalog đầy đủ: danh sách cơ sở + loại khoang của từng cơ sở. */
export async function fetchCatalog(): Promise<Catalog> {
  const facilities = await fetchFacilities();
  const lists = await Promise.all(facilities.map((facility) => fetchUnitTypes(facility.id)));
  const offerings: Record<number, UnitTypeOption[]> = {};
  const byId = new Map<number, UnitTypeOption>();
  facilities.forEach((facility, index) => {
    offerings[facility.id] = lists[index];
    lists[index].forEach((unitType) => byId.set(unitType.id, unitType));
  });
  const unitTypes = [...byId.values()].sort((left, right) => left.id - right.id);
  return { facilities, unitTypes, offerings };
}

/** "2 m²" */
export function formatArea(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return `${new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 2 }).format(Number(value))} m²`;
}

/** "1 × 2 × 2,2 m" */
export function formatDimensions(unitType: UnitTypeOption): string {
  const parts = [unitType.width, unitType.depth, unitType.height];
  if (parts.some((part) => part === null || part === undefined)) return "—";
  const format = new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 2 });
  return `${parts.map((part) => format.format(Number(part))).join(" × ")} m`;
}

// ---------------------------------------------------------------- Rental request (public)

export type CreateRentalRequestBody = {
  facilityId: number;
  customerName: string;
  customerEmail: string;
  customerPhone: string;
  unitTypeId: number;
  startDate: string;
  period: number;
};

export type RentalRequestCreated = {
  id: number;
  status: string;
  startDate: string;
  expiresAt: string;
};

export function createRentalRequest(body: CreateRentalRequestBody): Promise<RentalRequestCreated> {
  return apiRequest<RentalRequestCreated>("/api/rental-requests", { method: "POST", body });
}

// ---------------------------------------------------------------- Proposals

/** GET /api/proposals — CustomerProposalResponse. */
export type CustomerProposal = {
  id: number;
  orderId: number;
  status: string;
  note: string | null;
  createdAt: string;
  unitId: number;
  unitCode: string;
  unitSize: string | null;
  unitTypeName: string | null;
  facilityId: number;
  facilityName: string;
  facilityAddress: string | null;
  monthlyPrice: number | null;
  rejectionCount: number;
  maxRejectionCount: number;
};

/** ProposalView. */
export type ProposalSnapshot = {
  id: number;
  orderId: number;
  customerId: number;
  unitId: number;
  status: string;
  note: string | null;
  createdAt: string;
};

/** InvoiceView. */
export type InvoiceSnapshot = {
  id: number;
  orderId: number | null;
  code: string;
  title: string;
  description: string | null;
  type: string;
  status: string;
  amount: number;
  dueDate: string | null;
};

export type ProposalAgreeResult = { proposal: ProposalSnapshot; invoice: InvoiceSnapshot | null };
export type ProposalRejectResult = { proposal: ProposalSnapshot; orderCanceled: boolean };

export function fetchProposals(): Promise<CustomerProposal[]> {
  return authed<CustomerProposal[]>("/api/proposals");
}

export function agreeProposal(proposalId: number): Promise<ProposalAgreeResult> {
  return authed<ProposalAgreeResult>(`/api/proposals/${proposalId}/agree`, { method: "POST" });
}

export function rejectProposal(proposalId: number, note: string): Promise<ProposalRejectResult> {
  return authed<ProposalRejectResult>(`/api/proposals/${proposalId}/reject`, {
    method: "POST",
    body: { note },
  });
}

export const PROPOSAL_STATUS: Record<
  string,
  { label: string; tone: "success" | "warning" | "danger" | "neutral" }
> = {
  Pending: { label: "Chờ phản hồi", tone: "warning" },
  Agreed: { label: "Đã đồng ý", tone: "success" },
  Rejected: { label: "Đã từ chối", tone: "danger" },
  Canceled: { label: "Đã huỷ", tone: "neutral" },
};

// ---------------------------------------------------------------- Invoices & payment

/** GET /api/invoices — InvoiceListView. */
export type CustomerInvoice = {
  id: number;
  orderId: number | null;
  code: string;
  title: string;
  description: string | null;
  type: string;
  status: string;
  amount: number;
  createdAt: string;
  dueDate: string | null;
  unitId: number | null;
  unitCode: string | null;
  facilityId: number | null;
  facilityName: string | null;
};

/** POST /api/invoices/{id}/payment-check — InvoicePaymentService.PaymentCheck. */
export type PaymentCheck = {
  invoiceId: number;
  invoiceStatus: string;
  paymentStatus: string | null;
  failureReason: string | null;
  /** Đơn vừa trả cọc xong và đặt được lịch check-in ngay. */
  bookCheckinOrderId: number | null;
};

export const PENDING_INVOICE_KEY = "kho-moc-pending-invoice";

export function fetchInvoices(): Promise<CustomerInvoice[]> {
  return authed<CustomerInvoice[]>("/api/invoices");
}

export function checkInvoicePayment(invoiceId: number): Promise<PaymentCheck> {
  return authed<PaymentCheck>(`/api/invoices/${invoiceId}/payment-check`, { method: "POST" });
}

/**
 * POST /api/invoices/{id}/pay → chuyển trình duyệt sang cổng thanh toán.
 * 409 (hoá đơn bị từ chối / huỷ) ném `ApiError` với message của backend.
 */
export async function startInvoicePayment(invoiceId: number): Promise<void> {
  const { paymentUrl } = await authed<{ paymentUrl: string }>(`/api/invoices/${invoiceId}/pay`, {
    method: "POST",
  });
  try {
    window.sessionStorage.setItem(PENDING_INVOICE_KEY, String(invoiceId));
  } catch {
    // sessionStorage bị chặn — trang kết quả vẫn đọc được `?invoiceId=` nếu có.
  }
  window.location.href = paymentUrl;
}

export function readPendingInvoiceId(): number | null {
  try {
    const value = Number(window.sessionStorage.getItem(PENDING_INVOICE_KEY));
    return Number.isFinite(value) && value > 0 ? value : null;
  } catch {
    return null;
  }
}

export function clearPendingInvoiceId(): void {
  try {
    window.sessionStorage.removeItem(PENDING_INVOICE_KEY);
  } catch {
    // ignore
  }
}

/** Trạng thái nút "Thanh toán" dùng chung cho các trang có hoá đơn. */
export function useInvoicePayment(onRejected?: () => void) {
  const [payingId, setPayingId] = useState<number | null>(null);
  const [payError, setPayError] = useState<{ invoiceId: number; message: string } | null>(null);

  async function pay(invoiceId: number) {
    setPayingId(invoiceId);
    setPayError(null);
    try {
      await startInvoicePayment(invoiceId);
    } catch (caught) {
      setPayingId(null);
      setPayError({ invoiceId, message: errorMessage(caught) });
      if (errorStatus(caught) === 409) onRejected?.();
    }
  }

  return { payingId, payError, pay, clearPayError: () => setPayError(null) };
}

export const INVOICE_TYPE_LABEL: Record<string, string> = {
  Deposit: "Đặt cọc",
  DepositSupplement: "Bổ sung cọc",
  Rental: "Tiền thuê",
  Extension: "Gia hạn",
  Penalty: "Phí phạt / quyết toán",
  Service: "Dịch vụ",
};

export const INVOICE_STATUS: Record<
  string,
  { label: string; tone: "success" | "warning" | "danger" | "neutral" }
> = {
  Unpaid: { label: "Chưa thanh toán", tone: "warning" },
  Paid: { label: "Đã thanh toán", tone: "success" },
  Canceled: { label: "Đã huỷ", tone: "neutral" },
  Expired: { label: "Quá hạn", tone: "danger" },
};

// ---------------------------------------------------------------- Rental orders

/** OrderOverviewReadRepository.OrderOverviewRow. */
export type OrderOverviewRow = {
  id: number;
  status: string;
  cancelReason: string | null;
  expiresAt: string | null;
  needsReproposal: boolean;
  customerId: number;
  facilityId: number;
  facilityName: string;
  facilityAddress: string | null;
  unitTypeId: number | null;
  unitTypeName: string | null;
  startDate: string | null;
  period: number;
  requestedAt: string | null;
  customerName: string | null;
  customerEmail: string | null;
  customerPhone: string | null;
  unitId: number | null;
  unitCode: string | null;
  unitSize: string | null;
  unitStatus: string | null;
  monthlyPrice: number | null;
  proposalId: number | null;
  proposalStatus: string | null;
  invoiceId: number | null;
  invoiceCode: string | null;
  invoiceType: string | null;
  invoiceStatus: string | null;
  invoiceAmount: number | null;
  invoiceDueDate: string | null;
  appointmentId: number | null;
  appointmentDate: string | null;
  appointmentStart: string | null;
  appointmentEnd: string | null;
  appointmentStatus: string | null;
  staffId: number | null;
  handoverId: number | null;
  handoverResult: string | null;
  handoverDueAt: string | null;
  contractId: number | null;
  contractCode: string | null;
  contractStatus: string | null;
  contractStartDate: string | null;
  contractEndDate: string | null;
};

export type OrderAction =
  "RESPOND_PROPOSAL" | "PAY_INVOICE" | "BOOK_CHECKIN" | "HANDOVER" | "VIEW_CONTRACT" | "CANCEL";

/** GET /api/rental-orders — OrderOverview. */
export type OrderOverview = { order: OrderOverviewRow; availableActions: string[] };

export function fetchMyOrders(): Promise<OrderOverview[]> {
  return authed<OrderOverview[]>("/api/rental-orders");
}

export function fetchMyOrder(orderId: number): Promise<OrderOverview> {
  return authed<OrderOverview>(`/api/rental-orders/${orderId}`);
}

export function cancelMyOrder(
  orderId: number,
): Promise<{ orderId: number; status: string; cancelReason: string | null }> {
  return authed(`/api/rental-orders/${orderId}/cancel`, { method: "POST" });
}

export const ORDER_STATUS: Record<
  string,
  { label: string; tone: "success" | "warning" | "danger" | "info" | "neutral" }
> = {
  Pending: { label: "Chờ xử lý", tone: "warning" },
  Deposited: { label: "Đã đặt cọc", tone: "info" },
  Scheduled: { label: "Đã đặt lịch", tone: "info" },
  InProgress: { label: "Đang bàn giao", tone: "info" },
  Done: { label: "Hoàn tất", tone: "success" },
  Canceled: { label: "Đã huỷ", tone: "neutral" },
};

// ---------------------------------------------------------------- Appointment booking

export type AppointmentSlot = { startAt: string; endAt: string; available: boolean };
export type AppointmentDay = { date: string; slots: AppointmentSlot[] };

/** AppointmentBookingService.SlotWindow. */
export type SlotWindow = {
  orderId: number;
  facilityId: number;
  minDate: string;
  maxDate: string;
  days: AppointmentDay[];
};

/** AppointmentBookingService.BookedAppointment. */
export type BookedAppointment = {
  appointmentId: number;
  orderId: number;
  orderStatus: string;
  date: string;
  startAt: string;
  endAt: string;
  facilityName: string;
  facilityAddress: string | null;
};

export function fetchAppointmentSlots(orderId: number): Promise<SlotWindow> {
  return authed<SlotWindow>(`/api/rental-orders/${orderId}/appointment-slots`);
}

export function bookAppointment(
  orderId: number,
  date: string,
  startAt: string,
): Promise<BookedAppointment> {
  return authed<BookedAppointment>(`/api/rental-orders/${orderId}/appointments`, {
    method: "POST",
    body: { date, startAt },
  });
}

// ---------------------------------------------------------------- Tenancy (rentals)

/** ContractInvoiceService.ContractBill. */
export type ContractBill = {
  invoiceId: number;
  code: string;
  type: string;
  status: string;
  title: string;
  amount: number;
  dueDate: string | null;
  createdAt: string | null;
};

/** RentalContractService.AccessKeySnapshot. */
export type AccessKeySnapshot = {
  id: number;
  accessType: string;
  quantity: number | null;
  status: string;
};

/** TenancyService.ReturnRequestView. */
export type ReturnRequestView = {
  id: number;
  contractId: number;
  unitCode: string | null;
  facilityId: number;
  preferredDate: string;
  note: string | null;
  status: string;
  assignedStaffId: number | null;
  appointmentId: number | null;
  appointmentStart: string | null;
  /** FS đã xác nhận khách đến buổi trả kho — từ lúc này khách không huỷ yêu cầu được nữa. */
  appointmentArrivedAt: string | null;
  cancelReason: string | null;
  createdAt: string;
};

/** TenancyService.RentalView. */
export type RentalView = {
  contractId: number;
  contractCode: string;
  orderId: number;
  unitId: number;
  unitCode: string;
  facilityId: number;
  status: string;
  monthlyPrice: number;
  depositAmount: number;
  period: number;
  startDate: string;
  endDate: string;
  daysLeft: number;
  overdue: boolean;
  invoices: ContractBill[];
  accessKeys: AccessKeySnapshot[];
  returnRequest: ReturnRequestView | null;
  canExtend: boolean;
  canRequestReturn: boolean;
};

/** CheckoutService.CheckoutView. */
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

export function fetchRentals(): Promise<RentalView[]> {
  return authed<RentalView[]>("/api/customer/rentals");
}

export function fetchRental(contractId: number): Promise<RentalView> {
  return authed<RentalView>(`/api/customer/rentals/${contractId}`);
}

export function requestExtension(contractId: number, months: number): Promise<ContractBill> {
  return authed<ContractBill>(`/api/customer/rentals/${contractId}/extensions`, {
    method: "POST",
    body: { months },
  });
}

export function requestReturn(
  contractId: number,
  preferredDate: string,
  note: string | null,
): Promise<ReturnRequestView> {
  return authed<ReturnRequestView>(`/api/customer/rentals/${contractId}/return-request`, {
    method: "POST",
    body: { preferredDate, note },
  });
}

export function fetchMyReturnRequests(): Promise<ReturnRequestView[]> {
  return authed<ReturnRequestView[]>("/api/customer/return-requests");
}

export function cancelReturnRequest(returnRequestId: number): Promise<ReturnRequestView> {
  return authed<ReturnRequestView>(`/api/customer/return-requests/${returnRequestId}/cancel`, {
    method: "POST",
  });
}

export function fetchCheckout(returnRequestId: number): Promise<CheckoutView> {
  return authed<CheckoutView>(`/api/customer/return-requests/${returnRequestId}/checkout`);
}

export function rotateAccessCode(contractId: number): Promise<{ accessCode: string }> {
  return authed<{ accessCode: string }>(`/api/customer/contracts/${contractId}/access-code`, {
    method: "POST",
  });
}

export const RETURN_STATUS: Record<
  string,
  { label: string; tone: "success" | "warning" | "info" | "neutral" }
> = {
  Pending: { label: "Chờ phân công", tone: "warning" },
  Assigned: { label: "Đã phân công", tone: "info" },
  Completed: { label: "Đã hoàn tất", tone: "success" },
  Canceled: { label: "Đã huỷ", tone: "neutral" },
};

export const CHECKOUT_RESULT_LABEL: Record<string, string> = {
  IN_PROGRESS: "Đang kiểm tra",
  PENDING_ITEMS: "Khoang còn đồ",
  AWAITING_PAYMENT: "Chờ thanh toán phần còn thiếu",
  COMPLETED: "Đã hoàn tất",
};

export const ACCESS_TYPE_LABEL: Record<string, string> = {
  PhysicalKey: "Chìa khoá cơ",
  AccessCode: "Mã khoá số",
  Both: "Chìa khoá cơ + mã khoá số",
};

export const ACCESS_STATUS: Record<
  string,
  { label: string; tone: "success" | "danger" | "neutral" }
> = {
  Active: { label: "Đang dùng", tone: "success" },
  Revoked: { label: "Đã thu hồi", tone: "neutral" },
  Lost: { label: "Đã báo mất", tone: "danger" },
};

// ---------------------------------------------------------------- Handover

/** HandoverView.Contract. */
export type HandoverContract = {
  id: number;
  code: string;
  status: string;
  termsVersion: string | null;
  monthlyPrice: number | null;
  depositAmount: number | null;
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

/** HandoverView. */
export type HandoverView = {
  id: number;
  orderId: number;
  appointmentId: number | null;
  result: string;
  step: string;
  unitCode: string | null;
  unitType: string | null;
  facilityId: number;
  facilityName: string;
  facilityAddress: string | null;
  customerId: number;
  customerName: string | null;
  customerEmail: string | null;
  customerPhone: string | null;
  appointmentDate: string | null;
  appointmentStart: string | null;
  appointmentEnd: string | null;
  arrivedAt: string | null;
  identityStatus: string | null;
  identityVerifiedAt: string | null;
  inspectionStatus: string | null;
  unitInspectedAt: string | null;
  inspectionNotes: string | null;
  inspectionPhotos: string[];
  rejectReason: string | null;
  dueAt: string | null;
  completedAt: string | null;
  requestedAccess: string | null;
  customerConfirmedAt: string | null;
  contract: HandoverContract | null;
  firstMonthInvoice: {
    id: number;
    code: string;
    status: string;
    amount: number;
    dueDate: string | null;
  } | null;
  accessKeys: {
    id: number;
    accessType: string;
    quantity: number | null;
    status: string;
    issuedAt: string | null;
  }[];
  keyAccessEnabled: boolean;
  codeAccessEnabled: boolean;
  rejectionCount: number;
  maxRejectionCount: number;
};

export type HandoverRejectOutcome = {
  requiresConfirmation: boolean;
  orderCanceled: boolean;
  message: string;
};

export type HandoverConfirmOutcome = {
  completed: boolean;
  accessCode: string | null;
  message: string;
};

export type AccessChoice = "AccessCode" | "PhysicalKey" | "Both";

export function fetchMyHandovers(): Promise<HandoverView[]> {
  return authed<HandoverView[]>("/api/customer/handovers");
}

export function fetchMyHandover(handoverId: number): Promise<HandoverView> {
  return authed<HandoverView>(`/api/customer/handovers/${handoverId}`);
}

export function agreeHandoverInspection(
  handoverId: number,
  note: string | null,
): Promise<HandoverView> {
  return authed<HandoverView>(`/api/customer/handovers/${handoverId}/agree`, {
    method: "POST",
    body: { note },
  });
}

export function rejectHandover(
  handoverId: number,
  reason: string,
  confirmCancel: boolean,
): Promise<HandoverRejectOutcome> {
  return authed<HandoverRejectOutcome>(`/api/customer/handovers/${handoverId}/reject`, {
    method: "POST",
    body: { reason, confirmCancel },
  });
}

export function confirmHandover(
  handoverId: number,
  accessType: AccessChoice,
): Promise<HandoverConfirmOutcome> {
  return authed<HandoverConfirmOutcome>(`/api/customer/handovers/${handoverId}/confirm`, {
    method: "POST",
    body: { accessType },
  });
}

export const HANDOVER_STEP_LABEL: Record<string, string> = {
  WAITING_ARRIVAL: "Chờ bạn đến cơ sở",
  VERIFY_IDENTITY: "Nhân viên đang xác minh danh tính",
  INSPECTION: "Kiểm tra hiện trạng khoang",
  OVERRIDE_PENDING: "Chờ quản lý duyệt mốc tính tiền",
  SIGN_CONTRACT: "Ký hợp đồng tại cơ sở",
  PAY_FIRST_MONTH: "Thanh toán tiền thuê tháng đầu",
  CUSTOMER_CONFIRM: "Xác nhận biên bản bàn giao",
  HAND_OVER_KEYS: "Chờ nhân viên giao chìa khoá",
  COMPLETED: "Đã hoàn tất bàn giao",
  REJECTED: "Đã từ chối khoang",
  CANCELED: "Phiên bàn giao đã huỷ",
};

// ---------------------------------------------------------------- Contract document

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/+$/, "");

/**
 * GET /api/contracts/{id}/document trả `text/html` và cần bearer token, nên không mở được bằng
 * link thường: tải về rồi mở trong tab mới qua Blob URL.
 */
export async function openContractDocument(contractId: number): Promise<void> {
  // Mở tab trước khi await để trình duyệt không chặn popup.
  const target = window.open("", "_blank");
  const token = authGateway.getSession()?.token;
  try {
    const response = await fetch(`${API_BASE_URL}/api/contracts/${contractId}/document`, {
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    });
    const text = await response.text();
    if (!response.ok) {
      let message = `Request failed with status ${response.status}`;
      try {
        const parsed: unknown = JSON.parse(text);
        if (
          typeof parsed === "object" &&
          parsed !== null &&
          "message" in parsed &&
          typeof parsed.message === "string"
        ) {
          message = parsed.message;
        }
      } catch {
        // body không phải JSON
      }
      throw new ApiError(response.status, message);
    }
    const url = URL.createObjectURL(new Blob([text], { type: "text/html;charset=utf-8" }));
    if (target) {
      target.location.href = url;
    } else {
      window.open(url, "_blank");
    }
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch (caught) {
    target?.close();
    throw caught;
  }
}

/**
 * GET /api/contracts/{id}/document.pdf — PDF sinh từ đúng bản HTML ở trên (cùng quyền). Cần bearer token nên tải
 * qua fetch rồi lưu bằng Blob URL.
 */
export async function downloadContractPdf(contractId: number): Promise<void> {
  const token = authGateway.getSession()?.token;
  const response = await fetch(`${API_BASE_URL}/api/contracts/${contractId}/document.pdf`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
  if (!response.ok) {
    let message = `Request failed with status ${response.status}`;
    try {
      const parsed: unknown = await response.json();
      if (
        typeof parsed === "object" &&
        parsed !== null &&
        "message" in parsed &&
        typeof parsed.message === "string"
      ) {
        message = parsed.message;
      }
    } catch {
      // body không phải JSON
    }
    throw new ApiError(response.status, message);
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = `hop-dong-${contractId}.pdf`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

// ---------------------------------------------------------------- Support

/** SupportService.SupportView. */
export type SupportView = {
  id: number;
  customerId: number;
  customerName: string | null;
  customerEmail: string | null;
  facilityId: number | null;
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
  createdAt: string;
  assignedAt: string | null;
  resolvedAt: string | null;
};

export type SupportCategory = "LOST_KEY" | "ACCESS_CODE" | "UNIT_ISSUE" | "BILLING" | "OTHER";

export type CreateSupportBody = {
  contractId: number | null;
  facilityId: number | null;
  category: SupportCategory;
  title: string;
  description: string;
  photos: string[];
};

export function fetchSupportRequests(): Promise<SupportView[]> {
  return authed<SupportView[]>("/api/customer/support-requests");
}

export function createSupportRequest(body: CreateSupportBody): Promise<SupportView> {
  return authed<SupportView>("/api/customer/support-requests", { method: "POST", body });
}

export function cancelSupportRequest(id: number): Promise<SupportView> {
  return authed<SupportView>(`/api/customer/support-requests/${id}/cancel`, { method: "POST" });
}

export const SUPPORT_CATEGORY_LABEL: Record<SupportCategory, string> = {
  LOST_KEY: "Mất chìa khoá",
  ACCESS_CODE: "Mã khoá số",
  UNIT_ISSUE: "Sự cố khoang",
  BILLING: "Hoá đơn / thanh toán",
  OTHER: "Khác",
};

export const SUPPORT_STATUS: Record<
  string,
  { label: string; tone: "success" | "warning" | "info" | "neutral" }
> = {
  Open: { label: "Mới gửi", tone: "warning" },
  Assigned: { label: "Đã phân công", tone: "info" },
  Resolved: { label: "Đã xử lý", tone: "success" },
  Canceled: { label: "Đã huỷ", tone: "neutral" },
};

// ---------------------------------------------------------------- Loading helper

export type Resource<T> = {
  data: T | null;
  /** true khi đang tải (kể cả tải lại); `data` giữ giá trị cũ trong lúc tải lại. */
  loading: boolean;
  error: string | null;
  status: number;
  reload: () => void;
};

/**
 * Tải dữ liệu một lần theo `load` (phải ổn định — hàm module hoặc `useCallback`).
 * Đổi `load` hoặc gọi `reload()` thì tải lại.
 */
export function useResource<T>(load: () => Promise<T>): Resource<T> {
  const [nonce, setNonce] = useState(0);
  const [result, setResult] = useState<{
    load: () => Promise<T>;
    nonce: number;
    data: T | null;
    error: string | null;
    status: number;
  } | null>(null);

  useEffect(() => {
    let active = true;
    load().then(
      (data) => {
        if (active) setResult({ load, nonce, data, error: null, status: 200 });
      },
      (caught: unknown) => {
        if (active) {
          setResult((previous) => ({
            load,
            nonce,
            data: previous?.load === load ? previous.data : null,
            error: errorMessage(caught),
            status: errorStatus(caught),
          }));
        }
      },
    );
    return () => {
      active = false;
    };
  }, [load, nonce]);

  const fresh = result !== null && result.load === load && result.nonce === nonce;
  return {
    data: result !== null && result.load === load ? result.data : null,
    loading: !fresh,
    error: fresh ? result.error : null,
    status: fresh ? result.status : 0,
    reload: () => setNonce((value) => value + 1),
  };
}
