import type { UserRole } from "./auth";
import { View } from "./types";

// Trang chủ của mỗi role sau khi đăng nhập. Role chưa có trang (ADMIN/BOM)
// không có entry — LoginForm giữ thông báo "đang được hoàn thiện".
export const ROLE_HOME: Partial<Record<UserRole, string>> = {
  CUSTOMER: "/my-storage",
  FM: "/fm/rental-requests",
  FS: "/staff/schedule",
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
  if (path === "/staff/schedule") return "fs-schedule";
  if (path.startsWith("/staff/appointments/") && path.endsWith("/handover")) {
    return "fs-handover";
  }
  return "home";
}
