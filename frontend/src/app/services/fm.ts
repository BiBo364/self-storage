import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api";
import { authGateway } from "../auth";
import { fetchCurrentFmFacility, type FmFacility } from "../fmFacility";
import { authed, errorMessage, query } from "../http";

/*
 * Kiểu dữ liệu + hàm gọi API cho các màn Facility Manager. Tên field khớp đúng record Java
 * trả về (Jackson serialize theo tên component của record). Tiền (BigDecimal) là number,
 * Instant là chuỗi ISO, LocalDate là `yyyy-MM-dd`.
 */

// ------------------------------------------------------------------ Yêu cầu đặt kho

/** `booking/application/dto/RentalRequestSummaryResponse`. */
export type RentalRequestSummary = {
  id: number;
  customerName: string;
  customerEmail: string;
  customerPhone: string;
  unitTypeId: number;
  startDate: string;
  period: number;
  status: string;
  createdAt: string;
  expiresAt: string | null;
};

export type PageResult<T> = { items: T[]; page: number; size: number; total: number };

/** `ApproveRentalRequestResponse` — `status` có thể là `Rejected` khi tài khoản khách bị chặn. */
export type ApproveRentalRequestResult = {
  id: number;
  status: string;
  unitId: number | null;
  respondedAt: string;
  expiresAt: string | null;
};

export type RentalRequestRejectReason =
  "UNIT_UNAVAILABLE" | "UNIT_MISMATCH" | "CUSTOMER_REQUEST" | "OTHER";

export type RejectRentalRequestResult = {
  id: number;
  status: string;
  rejectReason: string;
  respondedAt: string;
};

export function fetchRentalRequests(
  facilityId: number,
  params: { status?: string; page?: number; size?: number },
): Promise<PageResult<RentalRequestSummary>> {
  return authed(`/api/fm/rental-requests${query({ facilityId, ...params })}`);
}

export function approveRentalRequest(
  requestId: number,
  unitId: number,
): Promise<ApproveRentalRequestResult> {
  return authed(`/api/fm/rental-requests/${requestId}/approve`, {
    method: "POST",
    body: { unitId },
  });
}

export function rejectRentalRequest(
  requestId: number,
  rejectReason: RentalRequestRejectReason,
  rejectNote?: string,
): Promise<RejectRentalRequestResult> {
  return authed(`/api/fm/rental-requests/${requestId}/reject`, {
    method: "POST",
    body: { rejectReason, rejectNote: rejectNote || undefined },
  });
}

// ------------------------------------------------------------------ Khoang + loại khoang

/** `facility/application/StorageUnitListView`. */
export type StorageUnit = {
  id: number;
  code: string;
  facilityId: number;
  unitTypeId: number;
  unitTypeName: string;
  size: string | null;
  area: number | null;
  status: string;
  monthlyPrice: number;
  /** Giá riêng của khoang; `null` = dùng giá loại khoang. */
  unitPrice: number | null;
  /** Mốc bảo trì sau trả kho (cron tự mở lại); `null` = không bảo trì hoặc bảo trì thủ công. */
  maintenanceStartedAt: string | null;
  /** Vị trí trên sơ đồ — cả ba `null` khi chưa xếp. */
  floorNo: number | null;
  aisleSide: "Left" | "Right" | null;
  aislePosition: number | null;
};

/** Vị trí khoang gửi lên: đủ ba trường, hoặc cả ba `null` để gỡ khỏi sơ đồ. */
export type UnitLocationInput = {
  floorNo: number | null;
  aisleSide: "Left" | "Right" | null;
  aislePosition: number | null;
};

/** `GET /api/unit-types?facilityId=` — loại khoang cơ sở cung cấp. */
export type UnitType = {
  id: number;
  name: string;
  width: number | null;
  depth: number | null;
  height: number | null;
  area: number | null;
  monthlyPrice: number;
};

/** `FacilityAdminCommands.UnitRow` — kết quả tạo / sửa / bảo trì khoang. */
export type UnitRow = {
  id: number;
  code: string;
  facilityId: number;
  unitTypeId: number;
  status: string;
  monthlyPrice: number | null;
  maintenanceStartedAt: string | null;
};

/** `FacilityAdminCommands.FacilityRow`. */
export type FacilityRow = {
  id: number;
  code: string;
  name: string;
  address: string | null;
  operatingHours: string | null;
  status: string;
  fmAccountId: number | null;
  fmEmail: string | null;
  keyAccess: boolean;
  codeAccess: boolean;
  unitCount: number;
  rentedCount: number;
  latitude: number | null;
  longitude: number | null;
  /** Số tầng — giới hạn tầng khi xếp khoang lên sơ đồ. */
  floorCount: number;
};

export function fetchStorageUnits(
  facilityId: number,
  params: { status?: string; unitTypeId?: number | string } = {},
): Promise<StorageUnit[]> {
  return authed(`/api/fm/storage-units${query({ facilityId, ...params })}`);
}

export function fetchUnitTypes(facilityId: number): Promise<UnitType[]> {
  return authed(`/api/unit-types${query({ facilityId })}`);
}

export function createStorageUnit(
  body: {
    facilityId: number;
    code: string;
    unitTypeId: number;
    monthlyPrice?: number | null;
  } & Partial<UnitLocationInput>,
): Promise<UnitRow> {
  return authed("/api/fm/storage-units", { method: "POST", body });
}

/** PUT /api/fm/storage-units/{id}/location — đặt / đổi / gỡ vị trí khoang (trùng vị trí → 409). */
export function setStorageUnitLocation(unitId: number, body: UnitLocationInput): Promise<UnitRow> {
  return authed(`/api/fm/storage-units/${unitId}/location`, { method: "PUT", body });
}

/** `monthlyPrice: null` = bỏ giá riêng, dùng giá loại khoang. */
export function updateStorageUnit(
  unitId: number,
  body: { unitTypeId?: number; monthlyPrice: number | null },
): Promise<UnitRow> {
  return authed(`/api/fm/storage-units/${unitId}`, { method: "PATCH", body });
}

export function setStorageUnitMaintenance(unitId: number, maintenance: boolean): Promise<UnitRow> {
  return authed(`/api/fm/storage-units/${unitId}/maintenance`, {
    method: "POST",
    body: { maintenance },
  });
}

/** Giữ bảo trì sau trả kho: bỏ mốc tự mở lại, khoang thành bảo trì thủ công. */
export function holdStorageUnitMaintenance(unitId: number): Promise<UnitRow> {
  return authed(`/api/fm/storage-units/${unitId}/maintenance-hold`, { method: "POST" });
}

/** Cơ sở kèm hình thức truy cập đang bật. */
export function fetchFacilityAccessOptions(facilityId: number): Promise<FacilityRow> {
  return authed(`/api/fm/facilities/${facilityId}/access-options`);
}

export function updateFacilityAccessOptions(
  facilityId: number,
  body: { keyAccess: boolean; codeAccess: boolean },
): Promise<FacilityRow> {
  return authed(`/api/fm/facilities/${facilityId}/access-options`, { method: "PUT", body });
}

// ------------------------------------------------------------------ Đơn thuê

/** `booking/repository/OrderOverviewReadRepository.OrderOverviewRow`. */
export type OrderOverviewRow = {
  id: number;
  status: string;
  cancelReason: string | null;
  expiresAt: string | null;
  needsReproposal: boolean;
  customerId: number | null;
  facilityId: number;
  facilityName: string | null;
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

/** `booking/application/dto/FmRentalOrderResponse`. */
export type FmRentalOrder = {
  id: number;
  status: string;
  cancelReason: string | null;
  expiresAt: string | null;
  unitId: number | null;
  facilityId: number;
  customer: { accountId: number | null; name: string | null; email: string; phone: string | null };
  requested: {
    unitTypeId: number;
    unitTypeName: string;
    startDate: string;
    period: number;
  };
  rejectionCount: number;
  maxRejectionCount: number;
  proposals: {
    id: number;
    unitId: number;
    unitCode: string;
    status: string;
    note: string | null;
    createdAt: string;
  }[];
  availableUnits: {
    id: number;
    code: string;
    size: string | null;
    monthlyPrice: number;
    previouslyRejected: boolean;
  }[];
};

export type CancelOrderResult = { orderId: number; status: string; cancelReason: string };

/** `booking/application/ProposalView`. */
export type ProposalView = {
  id: number;
  orderId: number;
  customerId: number;
  unitId: number;
  status: string;
  note: string | null;
  createdAt: string;
};

export function fetchRentalOrder(orderId: number): Promise<FmRentalOrder> {
  return authed(`/api/fm/rental-orders/${orderId}`);
}

export function cancelRentalOrder(orderId: number, reason: string): Promise<CancelOrderResult> {
  return authed(`/api/fm/rental-orders/${orderId}/cancel`, {
    method: "POST",
    body: { reason: reason.trim() || undefined },
  });
}

export function reproposeUnit(
  orderId: number,
  unitId: number,
  overrideReason?: string,
): Promise<ProposalView> {
  return authed(`/api/rental-orders/${orderId}/proposals`, {
    method: "POST",
    body: { unitId, overrideReason: overrideReason?.trim() || undefined },
  });
}

// ------------------------------------------------------------------ Lịch hẹn

/** `booking/application/dto/FmAppointmentResponse`. */
export type FmAppointment = {
  id: number;
  type: string;
  status: string;
  date: string;
  startedAt: string | null;
  endAt: string | null;
  orderId: number | null;
  orderStatus: string | null;
  unitCode: string | null;
  customerName: string | null;
  customerEmail: string | null;
  staffId: number | null;
  staffEmail: string | null;
};

export function fetchAppointments(facilityId: number, date: string): Promise<FmAppointment[]> {
  return authed(`/api/fm/appointments${query({ facilityId, date })}`);
}

export function assignAppointmentStaff(
  appointmentId: number,
  staffId: number,
): Promise<FmAppointment> {
  return authed(`/api/fm/appointments/${appointmentId}/staff`, {
    method: "POST",
    body: { staffId },
  });
}

/** Đổi FS của lịch check-in đã phân công, khách chưa đến (đơn giữ `InProgress`). */
export function reassignAppointmentStaff(
  appointmentId: number,
  staffId: number,
): Promise<FmAppointment> {
  return authed(`/api/fm/appointments/${appointmentId}/staff`, {
    method: "PUT",
    body: { staffId },
  });
}

// ------------------------------------------------------------------ Trả kho, quá hạn

/** `tenancy/application/TenancyService.ReturnRequestView`. */
export type ReturnRequest = {
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
  cancelReason: string | null;
  createdAt: string;
  /** Mốc FS xác nhận khách đến buổi trả kho; `null` = chưa đến / chưa có lịch. */
  appointmentArrivedAt: string | null;
};

/** `booking/application/ContractInvoiceService.ContractBill`. */
export type ContractBill = {
  invoiceId: number;
  code: string;
  type: string;
  status: string;
  title: string | null;
  amount: number;
  dueDate: string | null;
  createdAt: string;
};

/** `overdue/application/OverdueService.OverdueContract`. */
export type OverdueContract = {
  contractId: number;
  contractCode: string;
  orderId: number;
  unitCode: string | null;
  endDate: string;
  daysOverdue: number;
  chargedDays: number;
  accrued: number;
  openInvoice: ContractBill | null;
  returnRequested: boolean;
};

export function fetchReturnRequests(facilityId: number): Promise<ReturnRequest[]> {
  return authed(`/api/fm/return-requests${query({ facilityId })}`);
}

export function assignReturnRequest(requestId: number, staffId: number): Promise<ReturnRequest> {
  return authed(`/api/fm/return-requests/${requestId}/assign`, {
    method: "POST",
    body: { staffId },
  });
}

export function fetchOverdueContracts(facilityId: number): Promise<OverdueContract[]> {
  return authed(`/api/fm/overdue-contracts${query({ facilityId })}`);
}

// ------------------------------------------------------------------ Hỗ trợ

/** `support/application/SupportService.SupportView`. */
export type SupportRequest = {
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
  createdAt: string;
  assignedAt: string | null;
  resolvedAt: string | null;
};

export function fetchSupportRequests(
  facilityId: number,
  includeClosed: boolean,
): Promise<SupportRequest[]> {
  return authed(`/api/fm/support-requests${query({ facilityId, includeClosed })}`);
}

export function assignSupportRequest(requestId: number, staffId: number): Promise<SupportRequest> {
  return authed(`/api/fm/support-requests/${requestId}/assign`, {
    method: "POST",
    body: { staffId },
  });
}

export function resolveSupportRequest(
  requestId: number,
  body: { resolution: string; newKeyQuantity?: number; feeAmount?: number },
): Promise<SupportRequest> {
  return authed(`/api/fm/support-requests/${requestId}/resolve`, { method: "POST", body });
}

// ------------------------------------------------------------------ Báo cáo, hoá đơn, hợp đồng

/** `backoffice/repository/BackofficeReadRepository.Kpis`. */
export type ReportKpis = {
  pendingRequests: number;
  activeOrders: number;
  activeContracts: number;
  overdueContracts: number;
  openReturnRequests: number;
  openSupportRequests: number;
  unpaidAmount: number;
  /** Cọc đang giữ hộ khách (không phải doanh thu). */
  depositHeld: number;
};

/**
 * `BackofficeService.Report`; `revenue.month` dạng `yyyy-MM`, `revenue.type` là loại hoá đơn hoặc `ForfeitedDeposit`.
 */
export type FacilityReport = {
  from: string;
  to: string;
  kpis: ReportKpis;
  totalRevenue: number;
  revenue: { month: string; type: string; amount: number; count: number }[];
  occupancy: { facilityId: number; facilityName: string; status: string; count: number }[];
};

/** `BackofficeReadRepository.InvoiceRow`. */
export type InvoiceRow = {
  id: number;
  code: string;
  type: string;
  status: string;
  title: string | null;
  amount: number;
  createdAt: string;
  dueDate: string | null;
  paidAt: string | null;
  customerId: number | null;
  customerName: string | null;
  customerEmail: string | null;
  unitCode: string | null;
  facilityId: number | null;
  facilityName: string | null;
  orderId: number | null;
  contractId: number | null;
  contractCode: string | null;
};

/** `BackofficeReadRepository.ContractRow`. */
export type ContractRow = {
  id: number;
  code: string;
  status: string;
  orderId: number;
  customerId: number | null;
  customerName: string | null;
  customerEmail: string | null;
  customerPhone: string | null;
  unitCode: string | null;
  monthlyPrice: number | null;
  depositAmount: number | null;
  period: number;
  startDate: string | null;
  endDate: string | null;
  signedAt: string | null;
  unpaidAmount: number | null;
};

/** `handover/repository/HandoverReadRepository.OverrideRequestRow`. */
export type StartDateOverride = {
  contractId: number;
  contractCode: string;
  orderId: number;
  unitCode: string | null;
  customerName: string | null;
  startDate: string;
  requestedDate: string;
  reason: string | null;
  facilityName: string | null;
};

export function fetchFacilityReport(
  facilityId: number,
  range: { from?: string; to?: string },
): Promise<FacilityReport> {
  return authed(`/api/fm/reports${query({ facilityId, ...range })}`);
}

export function fetchFacilityContracts(
  facilityId: number,
  status?: string,
): Promise<ContractRow[]> {
  return authed(`/api/fm/contracts${query({ facilityId, status })}`);
}

export function fetchStartDateOverrides(): Promise<StartDateOverride[]> {
  return authed("/api/fm/contracts/start-date-overrides");
}

export function reviewStartDateOverride(
  contractId: number,
  decision: "approve" | "reject",
  reason?: string,
): Promise<null> {
  return authed(`/api/fm/contracts/${contractId}/start-date-override/${decision}`, {
    method: "POST",
    body: { reason: reason?.trim() || undefined },
  });
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").replace(/\/+$/, "");

/**
 * Bản hợp đồng là `text/html` cần bearer token, nên không mở thẳng URL được. Mở cửa sổ
 * trước (trong cử chỉ click, tránh popup bị chặn), tải HTML kèm token rồi gán Blob URL.
 */
export async function openContractDocument(contractId: number): Promise<void> {
  const popup = window.open("", "_blank");
  try {
    const response = await fetch(`${API_BASE_URL}/api/contracts/${contractId}/document`, {
      headers: {
        Accept: "text/html",
        Authorization: `Bearer ${authGateway.getSession()?.token ?? ""}`,
      },
    });
    const text = await response.text();
    if (!response.ok) {
      let message = `Request failed with status ${response.status}`;
      try {
        const body: unknown = JSON.parse(text);
        if (typeof body === "object" && body !== null && "message" in body) {
          message = String(body.message);
        }
      } catch {
        // Giữ thông báo mặc định khi body không phải JSON.
      }
      throw new ApiError(response.status, message);
    }
    const url = URL.createObjectURL(new Blob([text], { type: "text/html;charset=utf-8" }));
    if (popup) popup.location.href = url;
    else window.open(url, "_blank");
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch (caught) {
    popup?.close();
    throw caught;
  }
}

// ------------------------------------------------------------------ Nhãn hiển thị

type Tone = "success" | "warning" | "danger" | "info" | "neutral";

export const INVOICE_STATUS_LABEL: Record<string, string> = {
  Unpaid: "Chưa thanh toán",
  Paid: "Đã thanh toán",
  Canceled: "Đã huỷ",
  Expired: "Hết hạn",
};

export const INVOICE_STATUS_TONE: Record<string, Tone> = {
  Unpaid: "warning",
  Paid: "success",
  Canceled: "neutral",
  Expired: "danger",
};

export const INVOICE_TYPE_LABEL: Record<string, string> = {
  Deposit: "Tiền cọc",
  DepositSupplement: "Cọc bổ sung",
  Rental: "Tiền thuê",
  Extension: "Gia hạn",
  Penalty: "Phí phạt",
  Service: "Phí dịch vụ",
  ForfeitedDeposit: "Cọc bị giữ lại",
};

export const CONTRACT_STATUS_LABEL: Record<string, string> = {
  Draft: "Bản nháp",
  Signed: "Đã ký",
  Active: "Đang hiệu lực",
  Ended: "Đã kết thúc",
  Canceled: "Đã huỷ",
};

export const CONTRACT_STATUS_TONE: Record<string, Tone> = {
  Draft: "neutral",
  Signed: "info",
  Active: "success",
  Ended: "neutral",
  Canceled: "danger",
};

export const ORDER_STATUS_LABEL: Record<string, string> = {
  Pending: "Chưa cọc",
  Deposited: "Đã cọc",
  Scheduled: "Đã hẹn check-in",
  InProgress: "Đang bàn giao",
  Canceled: "Đã huỷ",
  Done: "Hoàn tất",
};

export const ORDER_STATUS_TONE: Record<string, Tone> = {
  Pending: "warning",
  Deposited: "info",
  Scheduled: "info",
  InProgress: "info",
  Canceled: "danger",
  Done: "success",
};

export const UNIT_STATUS_LABEL: Record<string, string> = {
  Available: "Trống",
  Reserved: "Đã giữ chỗ",
  Rented: "Đang cho thuê",
  Maintenance: "Bảo trì",
};

export const UNIT_STATUS_TONE: Record<string, Tone> = {
  Available: "success",
  Reserved: "warning",
  Rented: "info",
  Maintenance: "neutral",
};

export const PROPOSAL_STATUS_LABEL: Record<string, string> = {
  Pending: "Chờ khách phản hồi",
  Agreed: "Khách đồng ý",
  Rejected: "Khách từ chối",
  Canceled: "Đã huỷ",
};

export const PROPOSAL_STATUS_TONE: Record<string, Tone> = {
  Pending: "warning",
  Agreed: "success",
  Rejected: "danger",
  Canceled: "neutral",
};

/** Nhãn theo bảng; giá trị lạ hiển thị nguyên văn từ API. */
export function labelOf(map: Record<string, string>, value: string | null | undefined): string {
  if (!value) return "—";
  return map[value] ?? value;
}

export function toneOf(map: Record<string, Tone>, value: string | null | undefined): Tone {
  return (value && map[value]) || "neutral";
}

// ------------------------------------------------------------------ Tải dữ liệu

export type LoadError = { status: number; message: string };

function toLoadError(caught: unknown): LoadError {
  return caught instanceof ApiError
    ? { status: caught.status, message: caught.message }
    : { status: 0, message: errorMessage(caught) };
}

let facilityCache: { token: string | undefined; promise: Promise<FmFacility> } | null = null;

/** Cơ sở FM đang phụ trách — nhớ theo token để các màn không gọi lại `/api/fm/facility`. */
export function currentFmFacility(): Promise<FmFacility> {
  const token = authGateway.getSession()?.token;
  if (!facilityCache || facilityCache.token !== token) {
    const promise = fetchCurrentFmFacility();
    facilityCache = { token, promise };
    promise.catch(() => {
      if (facilityCache?.promise === promise) facilityCache = null;
    });
  }
  return facilityCache.promise;
}

type Settled = { token: string; error: LoadError | null };

/**
 * Tải dữ liệu theo cơ sở của FM. `key` đổi (vd đổi bộ lọc) hoặc gọi `reload()` thì tải lại;
 * dữ liệu lần trước được giữ trong lúc tải để danh sách không nháy.
 */
export function useFmLoad<T>(key: string, load: (facility: FmFacility) => Promise<T>) {
  const [round, setRound] = useState(0);
  const token = `${key}#${round}`;
  const [settled, setSettled] = useState<Settled | null>(null);
  const [latest, setLatest] = useState<{ facility: FmFacility; data: T } | null>(null);
  const loadRef = useRef(load);

  useEffect(() => {
    loadRef.current = load;
  });

  useEffect(() => {
    let active = true;
    currentFmFacility()
      .then(async (facility) => ({ facility, data: await loadRef.current(facility) }))
      .then(
        (result) => {
          if (!active) return;
          setLatest(result);
          setSettled({ token, error: null });
        },
        (caught: unknown) => {
          if (!active) return;
          setSettled({ token, error: toLoadError(caught) });
        },
      );
    return () => {
      active = false;
    };
  }, [token]);

  const reload = useCallback(() => setRound((value) => value + 1), []);
  const done = settled?.token === token;

  return {
    facility: latest?.facility ?? null,
    data: latest ? latest.data : null,
    loading: !done,
    error: done ? settled.error : null,
    reload,
  };
}

/** Props cho `SurfaceState` khi tải lỗi: 403 → không có quyền, 404 → không tìm thấy. */
export function loadErrorState(error: LoadError, subject: string) {
  if (error.status === 403) {
    return {
      variant: "forbidden" as const,
      title: `Bạn không có quyền xem ${subject}`,
      description: error.message,
    };
  }
  if (error.status === 404) {
    return {
      variant: "not-found" as const,
      title: `Không tìm thấy ${subject}`,
      description: error.message,
    };
  }
  return {
    variant: "error" as const,
    title: `Không tải được ${subject}`,
    description: error.message,
  };
}

/** Trạng thái một thao tác ghi: đang chạy (theo id), lỗi từ backend, thông báo thành công. */
export function useFmAction() {
  const [pending, setPending] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function run<R>(
    id: string,
    action: () => Promise<R>,
    success?: string | ((result: R) => string),
  ): Promise<{ ok: true; value: R } | { ok: false }> {
    setPending(id);
    setError(null);
    setNotice(null);
    try {
      const value = await action();
      if (success) setNotice(typeof success === "function" ? success(value) : success);
      return { ok: true, value };
    } catch (caught) {
      setError(errorMessage(caught));
      return { ok: false };
    } finally {
      setPending(null);
    }
  }

  function clear() {
    setError(null);
    setNotice(null);
  }

  return { pending, error, notice, run, clear };
}

// ---------------------------------------------------------------- Hoàn tiền thủ công

export type RefundEntry = { id: number; amount: number; note: string | null; recordedAt: string };
export type RefundSummary = { invoiceId: number; refundable: number; refunds: RefundEntry[] };

/** GET /api/fm/invoices/{id}/refunds — lịch sử hoàn + số còn hoàn được. */
export function fetchInvoiceRefunds(invoiceId: number): Promise<RefundSummary> {
  return authed<RefundSummary>(`/api/fm/invoices/${invoiceId}/refunds`);
}

/** POST /api/fm/invoices/{id}/refunds — FM ghi nhận đã chuyển khoản hoàn cho khách. */
export function recordInvoiceRefund(
  invoiceId: number,
  amount: number,
  note: string,
): Promise<RefundSummary> {
  return authed<RefundSummary>(`/api/fm/invoices/${invoiceId}/refunds`, {
    method: "POST",
    body: { amount, note },
  });
}
