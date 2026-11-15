import { authed, query } from "../http";
import type { StorageUnit } from "./fm";

// BOM — /api/bom/**. Field name khớp record Java của backend (backoffice, facility, pricing, identity).

/** `BackofficeReadRepository.Kpis` */
export type BomKpis = {
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
 * `BackofficeReadRepository.RevenueRow` — `month` dạng `YYYY-MM`; `type` là loại hoá đơn hoặc `ForfeitedDeposit`
 * (cọc của đơn bị huỷ mà cơ sở giữ lại). Khoản hoàn đã trừ vào `amount`.
 */
export type RevenueRow = { month: string; type: string; amount: number; count: number };

/** `BackofficeReadRepository.OccupancyRow` */
export type OccupancyRow = {
  facilityId: number;
  facilityName: string;
  status: string;
  count: number;
};

/** `BackofficeService.Report` */
export type BomReport = {
  from: string;
  to: string;
  kpis: BomKpis;
  totalRevenue: number;
  revenue: RevenueRow[];
  occupancy: OccupancyRow[];
};

/** `BackofficeReadRepository.InvoiceRow` */
export type InvoiceRow = {
  id: number;
  code: string;
  type: string;
  status: string;
  title: string | null;
  amount: number;
  createdAt: string | null;
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

/** `FacilityAdminCommands.FacilityRow` */
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
  /** Số FS `ACTIVE` đang được gán vào cơ sở. */
  staffCount: number;
  /** Ghim bản đồ — cùng `null` khi chưa ghim. */
  latitude: number | null;
  longitude: number | null;
  floorCount: number;
};

/** Khoang trên sơ đồ lúc tạo cơ sở (vị trí bắt buộc). */
export type LayoutUnitInput = {
  code: string;
  unitTypeId: number;
  floorNo: number;
  aisleSide: "Left" | "Right";
  aislePosition: number;
};

/** `PricingAdminCommands.PolicyRow` */
export type PolicyRow = {
  id: number;
  key: string;
  value: string;
  description: string | null;
  updatedAt: string | null;
};

/** `PricingAdminCommands.UnitTypeRow` */
export type UnitTypeRow = {
  id: number;
  name: string;
  width: number | null;
  depth: number | null;
  height: number | null;
  area: number | null;
  description: string | null;
  monthlyPrice: number | null;
};

/** `AccountAdminService.AccountRow` (dùng cho danh sách FM). */
export type ManagerRow = {
  id: number;
  email: string;
  name: string | null;
  phone: string | null;
  role: string;
  status: string;
};

export function fetchBomReport(params: {
  facilityId?: number | null;
  from?: string;
  to?: string;
}): Promise<BomReport> {
  return authed<BomReport>(`/api/bom/reports${query(params)}`);
}

export function fetchBomInvoices(params: {
  facilityId?: number | null;
  status?: string;
  type?: string;
}): Promise<InvoiceRow[]> {
  return authed<InvoiceRow[]>(`/api/bom/invoices${query(params)}`);
}

export function fetchBomFacilities(): Promise<FacilityRow[]> {
  return authed<FacilityRow[]>("/api/bom/facilities");
}

export function createBomFacility(body: {
  code: string;
  name: string;
  address?: string;
  operatingHours?: string;
  latitude?: number | null;
  longitude?: number | null;
  floorCount?: number;
  units?: LayoutUnitInput[];
}): Promise<FacilityRow> {
  return authed<FacilityRow>("/api/bom/facilities", { method: "POST", body });
}

export function updateBomFacility(
  id: number,
  body: {
    name?: string;
    address?: string;
    operatingHours?: string;
    status?: string;
    latitude?: number | null;
    longitude?: number | null;
    floorCount?: number;
  },
): Promise<FacilityRow> {
  return authed<FacilityRow>(`/api/bom/facilities/${id}`, { method: "PATCH", body });
}

/** GET /api/bom/facilities/{id}/units — mọi khoang kèm trạng thái + vị trí (sơ đồ toàn chuỗi). */
export function fetchBomFacilityUnits(id: number): Promise<StorageUnit[]> {
  return authed<StorageUnit[]>(`/api/bom/facilities/${id}/units`);
}

export function assignFacilityManager(
  id: number,
  fmAccountId: number | null,
): Promise<FacilityRow> {
  return authed<FacilityRow>(`/api/bom/facilities/${id}/manager`, {
    method: "PUT",
    body: { fmAccountId },
  });
}

export function setFacilityAccessOptions(
  id: number,
  keyAccess: boolean,
  codeAccess: boolean,
): Promise<FacilityRow> {
  return authed<FacilityRow>(`/api/bom/facilities/${id}/access-options`, {
    method: "PUT",
    body: { keyAccess, codeAccess },
  });
}

export function fetchBomManagers(): Promise<ManagerRow[]> {
  return authed<ManagerRow[]>("/api/bom/managers");
}

/** `AccountAdminService.AccountRow` của FS — `facilityIds` = cơ sở đang gán. */
export type StaffRow = ManagerRow & { facilityIds: number[] };

export function fetchBomStaff(): Promise<StaffRow[]> {
  return authed<StaffRow[]>("/api/bom/staff");
}

/** Thay toàn bộ cơ sở của FS; gỡ khỏi cơ sở mà FS còn việc mở → 409 (message liệt kê việc). */
export function setBomStaffFacilities(id: number, facilityIds: number[]): Promise<StaffRow> {
  return authed<StaffRow>(`/api/bom/staff/${id}/facilities`, {
    method: "PUT",
    body: { facilityIds },
  });
}

export function fetchBomPolicies(): Promise<PolicyRow[]> {
  return authed<PolicyRow[]>("/api/bom/policies");
}

export function updateBomPolicy(key: string, value: string): Promise<PolicyRow> {
  return authed<PolicyRow>(`/api/bom/policies/${encodeURIComponent(key)}`, {
    method: "PUT",
    body: { value },
  });
}

export function fetchBomUnitTypes(): Promise<UnitTypeRow[]> {
  return authed<UnitTypeRow[]>("/api/bom/unit-types");
}

export function updateUnitTypePrice(id: number, monthlyPrice: number): Promise<UnitTypeRow> {
  return authed<UnitTypeRow>(`/api/bom/unit-types/${id}/price`, {
    method: "PUT",
    body: { monthlyPrice },
  });
}

/** Nhãn tiếng Việt cho `invoices.type` / `invoices.status` (giá trị lấy từ enum backend). */
export const INVOICE_TYPE_LABEL: Record<string, string> = {
  Deposit: "Tiền cọc",
  DepositSupplement: "Cọc bổ sung",
  Rental: "Tiền thuê",
  Extension: "Gia hạn",
  Penalty: "Phí / quyết toán",
  Service: "Dịch vụ",
  ForfeitedDeposit: "Cọc bị giữ lại",
};

export const INVOICE_STATUS_LABEL: Record<string, string> = {
  Unpaid: "Chưa thanh toán",
  Paid: "Đã thanh toán",
  Canceled: "Đã huỷ",
  Expired: "Quá hạn",
};

// ------------------------------------------------------------------ yêu cầu tạo tài khoản (BOM → ADMIN)

export type AccountRequestRole = "FM" | "FS";
export type AccountRequestStatus = "Pending" | "Approved" | "Rejected" | "Canceled";

/** `AccountRequestService.AccountRequestRow` */
export type AccountRequestRow = {
  id: number;
  requestedById: number;
  requestedByEmail: string;
  requestedByName: string | null;
  role: AccountRequestRole;
  email: string;
  name: string;
  phone: string | null;
  facilityIds: number[];
  facilityCodes: string[];
  note: string | null;
  status: AccountRequestStatus;
  reviewedById: number | null;
  reviewedByEmail: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  createdAccountId: number | null;
  createdAt: string;
  /** Người nghỉ việc được thay (cùng role); `null` = tuyển thêm. */
  replacesAccountId: number | null;
  replacesEmail: string | null;
  replacesName: string | null;
};

export type AccountRequestInput = {
  role: AccountRequestRole;
  email: string;
  name: string;
  phone?: string;
  facilityIds?: number[];
  note?: string;
  /** Thay FM / FS nghỉ việc — cơ sở lấy theo người cũ, `facilityIds` bị bỏ qua. */
  replacesAccountId?: number;
};

export const ACCOUNT_REQUEST_ROLE_LABEL: Record<AccountRequestRole, string> = {
  FM: "Quản lý cơ sở (FM)",
  FS: "Nhân viên cơ sở (FS)",
};

export const ACCOUNT_REQUEST_STATUS_LABEL: Record<AccountRequestStatus, string> = {
  Pending: "Chờ Admin duyệt",
  Approved: "Đã tạo tài khoản",
  Rejected: "Bị từ chối",
  Canceled: "Đã huỷ",
};

export function fetchAccountRequests(): Promise<AccountRequestRow[]> {
  return authed<AccountRequestRow[]>("/api/bom/account-requests");
}

export function submitAccountRequest(input: AccountRequestInput): Promise<AccountRequestRow> {
  return authed<AccountRequestRow>("/api/bom/account-requests", { method: "POST", body: input });
}

export function cancelAccountRequest(id: number): Promise<AccountRequestRow> {
  return authed<AccountRequestRow>(`/api/bom/account-requests/${id}/cancel`, { method: "POST" });
}
