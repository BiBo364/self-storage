import { authed, query } from "../http";

// Admin — /api/admin/**. Field name khớp record Java (identity/AccountAdminService, BackofficeReadRepository).

/** `AccountAdminService.AccountRow` */
export type AccountRow = {
  id: number;
  email: string;
  name: string | null;
  phone: string | null;
  role: string;
  status: string;
  verified: boolean;
  createdAt: string | null;
  facilityIds: number[];
  lastLoginAt: string | null;
};

/** `AccountAdminService.LoginEventRow` */
export type LoginEventRow = {
  id: number;
  accountId: number | null;
  email: string;
  success: boolean;
  reason: string | null;
  ipAddress: string | null;
  createdAt: string;
};

/** `AccountAdminService.PermissionRow` — `scopeByRole[role]` = `GLOBAL` | `FACILITY`, thiếu = không có quyền. */
export type PermissionRow = {
  code: string;
  description: string | null;
  scopeByRole: Record<string, string>;
};

/** `BackofficeReadRepository.AuditRow` — `oldValue`/`newValue` là chuỗi JSON. */
export type AuditRow = {
  id: number;
  action: string;
  entityType: string;
  entityId: string | null;
  actorId: number | null;
  actorEmail: string | null;
  oldValue: string | null;
  newValue: string | null;
  reason: string | null;
  createdAt: string;
};

/** `FacilityCatalogView` — GET /api/facilities (public, chỉ cơ sở `Active`). */
export type PublicFacility = {
  id: number;
  code: string;
  name: string;
  address: string | null;
  operatingHours: string | null;
  unitTypeIds: number[];
};

export const ROLE_OPTIONS = ["ADMIN", "BOM", "FM", "FS", "CUSTOMER"] as const;
export const ACCOUNT_STATUS_OPTIONS = ["ACTIVE", "BANNED"] as const;

export const ROLE_LABEL: Record<string, string> = {
  ADMIN: "Admin",
  BOM: "BOM",
  FM: "FM",
  FS: "FS",
  CUSTOMER: "Khách hàng",
};

export const ACCOUNT_STATUS_LABEL: Record<string, string> = {
  ACTIVE: "Đang hoạt động",
  BANNED: "Đã khoá",
};

export function fetchAccounts(params: {
  role?: string;
  status?: string;
  q?: string;
  page?: number;
  size?: number;
}): Promise<AccountRow[]> {
  return authed<AccountRow[]>(`/api/admin/accounts${query(params)}`);
}

export function createAccount(body: {
  email: string;
  password: string;
  name?: string;
  phone?: string;
  role: string;
}): Promise<AccountRow> {
  return authed<AccountRow>("/api/admin/accounts", { method: "POST", body });
}

export function updateAccount(
  id: number,
  body: { role?: string; status?: string; name?: string; phone?: string },
): Promise<AccountRow> {
  return authed<AccountRow>(`/api/admin/accounts/${id}`, { method: "PATCH", body });
}

export function setAccountFacilities(id: number, facilityIds: number[]): Promise<AccountRow> {
  return authed<AccountRow>(`/api/admin/accounts/${id}/facilities`, {
    method: "PUT",
    body: { facilityIds },
  });
}

export function fetchPublicFacilities(): Promise<PublicFacility[]> {
  return authed<PublicFacility[]>("/api/facilities");
}

export function fetchLoginEvents(params: {
  email?: string;
  page?: number;
  size?: number;
}): Promise<LoginEventRow[]> {
  return authed<LoginEventRow[]>(`/api/admin/login-events${query(params)}`);
}

export function fetchAuditLogs(params: {
  action?: string;
  entityType?: string;
  entityId?: string;
  actor?: string;
  page?: number;
  size?: number;
}): Promise<AuditRow[]> {
  return authed<AuditRow[]>(`/api/admin/audit-logs${query(params)}`);
}

export function fetchPermissionMatrix(): Promise<PermissionRow[]> {
  return authed<PermissionRow[]>("/api/admin/permissions");
}

export function setRolePermissions(
  role: string,
  scopes: Record<string, string>,
): Promise<PermissionRow[]> {
  return authed<PermissionRow[]>(`/api/admin/roles/${encodeURIComponent(role)}/permissions`, {
    method: "PUT",
    body: scopes,
  });
}
