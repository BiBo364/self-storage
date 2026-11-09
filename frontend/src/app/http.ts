import { ApiError, apiRequest } from "./api";

// Upload file: dùng `uploadMedia` trong ./media.
import { authGateway } from "./auth";

type Method = "GET" | "POST" | "PATCH" | "DELETE" | "PUT";

/** Gọi API kèm token của phiên hiện tại — dùng chung cho mọi màn đã đăng nhập. */
export function authed<T>(
  path: string,
  options: { method?: Method; body?: unknown; signal?: AbortSignal } = {},
): Promise<T> {
  return apiRequest<T>(path, {
    method: options.method,
    body: options.body,
    signal: options.signal,
    accessToken: authGateway.getSession()?.token,
  });
}

/** Thông báo lỗi hiển thị cho người dùng từ một lỗi bất kỳ. */
export function errorMessage(caught: unknown): string {
  if (caught instanceof ApiError) return caught.message;
  return "Không thể kết nối tới máy chủ.";
}

/** Mã HTTP của lỗi (0 = lỗi mạng). */
export function errorStatus(caught: unknown): number {
  return caught instanceof ApiError ? caught.status : 0;
}

/** Query string từ object, bỏ giá trị rỗng. */
export function query(params: Record<string, string | number | boolean | null | undefined>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== "") search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : "";
}
