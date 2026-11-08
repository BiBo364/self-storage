import { authed, query } from "../http";

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
}): Promise<FacilityRow> {
  return authed<FacilityRow>("/api/bom/facilities", { method: "POST", body });
}

export function updateBomFacility(
  id: number,
  body: { name?: string; address?: string; operatingHours?: string; status?: string },
): Promise<FacilityRow> {
  return authed<FacilityRow>(`/api/bom/facilities/${id}`, { method: "PATCH", body });
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
