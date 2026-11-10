import { useEffect, useSyncExternalStore } from "react";
import { apiRequest } from "./api";
import { authGateway, type UserRole } from "./auth";

export type NotificationGroup = "approval" | "invoice" | "assignment" | "expiry" | "other";

export type NotificationItem = {
  id: number;
  type: string;
  title: string;
  body: string;
  readAt: string | null;
  createdAt: string;
  orderId: number | null;
};

/** Summary từ BE không có `body`; dialog chi tiết lấy `body` qua `fetchNotificationDetail`. */
export type NotificationSummary = Omit<NotificationItem, "body">;

const GROUP_BY_TYPE: Record<string, NotificationGroup> = {
  PROPOSAL_REJECTED: "approval",
  PROPOSAL_REPROPOSAL_REQUIRED: "approval",
  PROPOSAL_REPROPOSED: "approval",
  RENTAL_REQUEST_APPROVED: "approval",
  RENTAL_REQUEST_REJECTED: "approval",
  HANDOVER_REJECTED: "approval",
  DEPOSIT_PAYMENT_SUCCEEDED: "invoice",
  APPOINTMENT_CREATED: "assignment",
  APPOINTMENT_CANCELED_NO_SHOW: "assignment",
  FS_ASSIGNED: "assignment",
  FS_ASSIGNMENT_REQUIRED: "assignment",
  HANDOVER_OVERDUE: "assignment",
  HANDOVER_COMPLETED: "assignment",
  RENTAL_ORDER_EXPIRING_SOON: "expiry",
  RENTAL_ORDER_CANCELED: "expiry",
};

export function notificationGroup(type: string): NotificationGroup {
  return GROUP_BY_TYPE[type] ?? "other";
}

/**
 * Deep link theo `type` + `orderId` cho từng role. `OTHER` và các type chưa có
 * màn hình (vd FS) không gắn link — bấm chỉ để đánh dấu đã đọc.
 */
export function notificationPath(
  item: { type: string; orderId: number | null },
  role: UserRole,
): string | undefined {
  const orderId = item.orderId;
  switch (item.type) {
    case "PROPOSAL_REPROPOSAL_REQUIRED":
    case "HANDOVER_REJECTED":
      if (role === "FM") {
        return orderId ? `/fm/rental-orders/${orderId}/re-propose` : "/fm/rental-requests";
      }
      return undefined;
    case "PROPOSAL_REJECTED":
      // Khách từ chối đề xuất: việc cần làm là đề xuất lại khoang, nằm ở trang đơn thuê — không phải
      // hàng đợi yêu cầu đặt kho (tab "Từ chối" ở đó là yêu cầu bị FM từ chối).
      if (role === "FM")
        return orderId ? `/fm/rental-orders/${orderId}/re-propose` : "/fm/rental-orders";
      return role === "CUSTOMER" ? "/my-storage" : undefined;
    case "RENTAL_ORDER_CANCELED":
      if (role === "FM") return "/fm/rental-orders";
      return role === "CUSTOMER" ? "/my-storage" : undefined;
    case "APPOINTMENT_CREATED":
      // Customer chưa có màn xem lịch đã tạo (chỉ có form đặt mới) — chỉ mark read.
      return role === "FM" ? "/fm/appointments" : undefined;
    case "FS_ASSIGNMENT_REQUIRED":
      return role === "FM" ? "/fm/appointments" : undefined;
    case "RENTAL_REQUEST_APPROVED":
    case "RENTAL_REQUEST_REJECTED":
    case "PROPOSAL_REPROPOSED":
      return role === "CUSTOMER" ? "/proposals" : undefined;
    case "DEPOSIT_PAYMENT_SUCCEEDED":
      return role === "CUSTOMER" ? "/invoices" : undefined;
    case "APPOINTMENT_CANCELED_NO_SHOW":
    case "HANDOVER_OVERDUE":
      return role === "CUSTOMER" ? "/appointments/new" : undefined;
    case "RENTAL_ORDER_EXPIRING_SOON":
    case "HANDOVER_COMPLETED":
      return role === "CUSTOMER" ? "/my-storage" : undefined;
    case "HANDOVER_KEY_REQUESTED":
      return role === "FS" ? "/staff/handovers" : undefined;
    case "FIRST_MONTH_REFUND_REQUIRED":
    case "DEPOSIT_REFUND_REQUIRED":
    case "CHECKOUT_REFUND_REQUIRED":
    case "CHECKOUT_SHORTFALL_UNPAID":
    case "RENT_UNPAID":
      return role === "FM" ? "/fm/invoices" : role === "CUSTOMER" ? "/invoices" : undefined;
    case "RENT_INVOICE_ISSUED":
      return role === "CUSTOMER" ? "/invoices" : undefined;
    case "CONTRACT_ACCESS_SUSPENDED":
      return role === "FM"
        ? "/fm/extend-requests"
        : role === "CUSTOMER"
          ? "/my-storage"
          : undefined;
    case "CONTRACT_ACCESS_RESTORED":
    case "RETURN_DATE_SHIFTED":
      return role === "CUSTOMER" ? "/my-storage" : undefined;
    default:
      return undefined;
  }
}

export function formatNotificationTime(iso: string): string {
  const date = new Date(iso);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${pad(date.getDate())}/${pad(date.getMonth() + 1)}/${date.getFullYear()} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function accessToken(): string | undefined {
  return authGateway.getSession()?.token;
}

/**
 * Summary cho dropdown chuông — 1 request, chỉ title/thời gian (không cần body).
 */
export async function fetchNotificationSummaries(size: number): Promise<NotificationSummary[]> {
  return apiRequest<NotificationSummary[]>(`/api/notifications?page=0&size=${size}`, {
    accessToken: accessToken(),
  });
}

/**
 * Một trang danh sách: chỉ summary (title/thời gian). `body` chỉ lấy khi mở dialog
 * chi tiết — không fetch detail từng item (hết N+1).
 */
export async function fetchNotificationPage(
  page: number,
  size: number,
  isRead?: boolean,
): Promise<NotificationSummary[]> {
  const readParam = isRead === undefined ? "" : `&is_read=${isRead}`;
  return apiRequest<NotificationSummary[]>(
    `/api/notifications?page=${page}&size=${size}${readParam}`,
    { accessToken: accessToken() },
  );
}

/** Chi tiết 1 thông báo (đủ `body`) — gọi khi mở dialog chi tiết. */
export async function fetchNotificationDetail(id: number): Promise<NotificationItem> {
  return apiRequest<NotificationItem>(`/api/notifications/${id}`, {
    accessToken: accessToken(),
  });
}

export async function markNotificationRead(id: number): Promise<void> {
  await apiRequest(`/api/notifications/${id}/read`, {
    method: "PATCH",
    accessToken: accessToken(),
  });
  await refreshUnreadCount();
}

export async function markAllNotificationsRead(): Promise<void> {
  await apiRequest("/api/notifications/read-all", { method: "PATCH", accessToken: accessToken() });
  await refreshUnreadCount();
}

/** Store nhỏ cho badge chuông + sidebar, dùng chung giữa các shell. */
let unreadCount = 0;
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((listener) => listener());
}

export function getUnreadCount() {
  return unreadCount;
}

export function subscribeUnread(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

// Đăng xuất / đổi account → reset badge ngay, tránh số chưa đọc của account cũ.
authGateway.subscribe(() => {
  unreadCount = 0;
  emit();
});

let unreadRequest: Promise<void> | null = null;

export function refreshUnreadCount(): Promise<void> {
  // Single-flight: nhiều consumer (chuông + sidebar) mount cùng lúc chỉ gọi 1 request.
  if (unreadRequest) return unreadRequest;
  unreadRequest = (async () => {
    try {
      const { unreadCount: next } = await apiRequest<{ unreadCount: number }>(
        "/api/notifications/unread-count",
        { accessToken: accessToken() },
      );
      unreadCount = next;
      emit();
    } catch {
      // Lỗi mạng thì giữ số cũ; lần refresh sau sẽ cập nhật.
    } finally {
      unreadRequest = null;
    }
  })();
  return unreadRequest;
}

export function useUnreadNotificationCount(): number {
  const count = useSyncExternalStore(subscribeUnread, getUnreadCount, () => 0);
  useEffect(() => {
    void refreshUnreadCount();
  }, []);
  return count;
}
