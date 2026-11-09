import { Search } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { formatDateTime } from "../../app/format";
import { errorMessage, errorStatus } from "../../app/http";
import { fetchLoginEvents, type LoginEventRow } from "../../app/services/admin";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const PAGE_SIZE = 50;

const REASON_LABEL: Record<string, string> = {
  InvalidCredentials: "Sai email hoặc mật khẩu",
  AccountNotAllowed: "Tài khoản bị khoá",
  EmailNotVerified: "Chưa xác minh email",
  TooManyLoginAttempts: "Đăng nhập sai quá nhiều lần",
};

/** Admin — lịch sử đăng nhập. */
export function AdminLoginEventsPage({ navigate }: { navigate: Navigate }) {
  void navigate;
  const [searchText, setSearchText] = useState("");
  const [email, setEmail] = useState("");
  const [page, setPage] = useState(0);
  const [events, setEvents] = useState<LoginEventRow[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const list = await fetchLoginEvents({ email, page, size: PAGE_SIZE });
        if (active) setEvents(list);
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [email, page, reloadKey]);

  function change(apply: () => void) {
    apply();
    setIsLoading(true);
    setError(null);
  }

  function search(event: FormEvent) {
    event.preventDefault();
    change(() => {
      setEmail(searchText.trim());
      setPage(0);
    });
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Lịch sử đăng nhập</h1>
          <p className="mt-1.5 text-small text-muted">
            Mọi lần đăng nhập thành công và thất bại, mới nhất trước.
          </p>
        </div>
        <form className="grid min-w-[280px] gap-1.5" onSubmit={search}>
          <label className="text-label text-muted" htmlFor="login-email">
            Lọc theo email
          </label>
          <div className={INPUT_WRAP}>
            <Search size={15} />
            <input
              id="login-email"
              className={INPUT_ELEMENT}
              value={searchText}
              onChange={(event) => setSearchText(event.target.value)}
              placeholder="Nhập email rồi nhấn Enter"
            />
          </div>
        </form>
      </div>

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải lịch sử đăng nhập…" />
      ) : error ? (
        error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xem lịch sử đăng nhập"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được lịch sử đăng nhập"
            description={error.message}
            action={{ label: "Thử lại", onClick: () => change(() => setReloadKey((k) => k + 1)) }}
          />
        )
      ) : events.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={
            page > 0
              ? "Hết dữ liệu"
              : email
                ? `Không có lần đăng nhập nào của ${email}`
                : "Chưa có lần đăng nhập nào"
          }
          action={
            page > 0
              ? { label: "Về trang đầu", onClick: () => change(() => setPage(0)) }
              : email
                ? {
                    label: "Xoá bộ lọc",
                    onClick: () =>
                      change(() => {
                        setEmail("");
                        setSearchText("");
                      }),
                  }
                : undefined
          }
        />
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] border-separate border-spacing-y-1.5 text-small">
              <thead>
                <tr className="bg-ink text-left text-label text-background">
                  <th className="px-3.5 py-2.5 font-semibold">Thời điểm</th>
                  <th className="px-3.5 py-2.5 font-semibold">Email</th>
                  <th className="px-3.5 py-2.5 font-semibold">Kết quả</th>
                  <th className="px-3.5 py-2.5 font-semibold">Lý do</th>
                  <th className="px-3.5 py-2.5 font-semibold">IP</th>
                </tr>
              </thead>
              <tbody>
                {events.map((event) => (
                  <tr key={event.id} className="bg-surface">
                    <td className="px-3.5 py-3 tabular-nums text-muted">
                      {formatDateTime(event.createdAt)}
                    </td>
                    <td className="px-3.5 py-3 text-ink">
                      {event.email || "—"}
                      {event.accountId === null && (
                        <span className="ml-2 text-label text-muted">(không có tài khoản)</span>
                      )}
                    </td>
                    <td className="px-3.5 py-3">
                      <StatusTag tone={event.success ? "success" : "danger"}>
                        {event.success ? "Thành công" : "Thất bại"}
                      </StatusTag>
                    </td>
                    <td className="px-3.5 py-3 text-muted">
                      {event.reason ? (REASON_LABEL[event.reason] ?? event.reason) : "—"}
                    </td>
                    <td className="px-3.5 py-3 tabular-nums text-muted">
                      {event.ipAddress ?? "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex items-center justify-end gap-3">
            <span className="text-label text-muted">Trang {page + 1}</span>
            <Button
              size="sm"
              variant="secondary"
              disabled={page === 0}
              onClick={() => change(() => setPage((p) => Math.max(0, p - 1)))}
            >
              Trang trước
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={events.length < PAGE_SIZE}
              onClick={() => change(() => setPage((p) => p + 1))}
            >
              Trang sau
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
