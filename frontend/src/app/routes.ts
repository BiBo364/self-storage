import type { UserRole } from "./auth";
import { View } from "./types";

// Trang chủ của mỗi role sau khi đăng nhập.
export const ROLE_HOME: Partial<Record<UserRole, string>> = {
  CUSTOMER: "/my-storage",
  FM: "/fm/rental-requests",
  FS: "/staff/schedule",
  BOM: "/bom/dashboard",
  ADMIN: "/admin/accounts",
};

export function viewFromLocation(): View {
  const path = window.location.pathname;
  if (path === "/units") return "units";
  if (path === "/login") return "login";
  if (path === "/register") return "register";
  if (path === "/verify-email") return "verify";
  if (path === "/rental-requests/new") return "request";
  if (path === "/my-storage") return "my-storage";
  if (path.startsWith("/my-storage/")) return "contract-detail";
  if (path === "/proposals") return "proposals";
  if (path === "/invoices") return "invoices";
  if (path === "/appointments/new") return "appointments";
  if (path === "/notifications") return "notifications";
  if (path.startsWith("/handovers/")) return "handover";
  if (path === "/payments/result") return "payment-result";
  if (path === "/support") return "support";
  if (path === "/staff/handovers") return "fs-handovers";
  if (path === "/staff/returns") return "fs-returns";
  if (path.startsWith("/staff/returns/")) return "fs-return-detail";
  if (path === "/staff/support") return "fs-support";
  if (path === "/bom/dashboard") return "bom-dashboard";
  if (path === "/bom/facilities") return "bom-facilities";
  if (path === "/bom/staff") return "bom-staff";
  if (path === "/bom/pricing") return "bom-pricing";
  if (path === "/bom/invoices") return "bom-invoices";
  if (path === "/admin/accounts") return "admin-accounts";
  if (path === "/admin/permissions") return "admin-permissions";
  if (path === "/admin/login-events") return "admin-login-events";
  if (path === "/admin/audit-logs") return "admin-audit-logs";
  if (path === "/fm/rental-requests") return "fm-rental-requests";
  if (path === "/fm/rental-orders") return "fm-orders";
  if (path.startsWith("/fm/rental-orders/") && path.endsWith("/re-propose")) {
    return "fm-proposal-redo";
  }
  if (path === "/fm/appointments") return "fm-appointments";
  if (path === "/fm/return-requests") return "fm-return-requests";
  if (path === "/fm/extend-requests") return "fm-extend-requests";
  if (path === "/fm/support-requests") return "fm-support-requests";
  if (path === "/fm/storage-units") return "fm-storage-units";
  if (path === "/fm/staff") return "fm-staff";
  if (path === "/fm/reports") return "fm-reports";
  if (path === "/fm/invoices") return "fm-invoices";
  if (path === "/fm/contracts") return "fm-contracts";
  if (path === "/admin/reconciliation" || path === "/admin/payments") {
    return "admin-payments";
  }
  if (path === "/staff/schedule") return "fs-schedule";
  if (path.startsWith("/staff/appointments/") && path.endsWith("/handover")) {
    return "fs-handover";
  }
  return "home";
}

/** Phần cuối của đường dẫn hiện tại dạng số (vd `/handovers/12` → 12), hoặc tham số `?key=`. */
export function pathId(segmentIndex: number): number | null {
  const parts = window.location.pathname.split("/").filter(Boolean);
  const value = Number(parts[segmentIndex]);
  return Number.isFinite(value) && value > 0 ? value : null;
}

export function queryParam(key: string): string | null {
  return new URLSearchParams(window.location.search).get(key);
}
