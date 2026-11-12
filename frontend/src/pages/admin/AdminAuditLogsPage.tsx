import { useEffect, useState, type FormEvent } from "react";
import { formatDateTime } from "../../app/format";
import { errorMessage, errorStatus } from "../../app/http";
import { fetchAuditLogs, type AuditRow } from "../../app/services/admin";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FIELD_LABEL } from "../../components/ui/FormField";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { SurfaceState } from "../../components/ui/SurfaceState";

const PAGE_SIZE = 50;
const INPUT =
  "min-h-[42px] w-full rounded-lg border border-rule-strong bg-surface px-3 text-small text-ink outline-0 focus:border-brand";

type Filters = { action: string; entityType: string; entityId: string; actor: string };
const EMPTY: Filters = { action: "", entityType: "", entityId: "", actor: "" };

/** `oldValue`/`newValue` là chuỗi JSON — format lại để đọc; không parse được thì giữ nguyên. */
function pretty(value: string | null): string {
  if (value === null || value === "") return "—";
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

/** Admin — nhật ký thao tác. */
export function AdminAuditLogsPage({ navigate }: { navigate: Navigate }) {
  void navigate;
  const [form, setForm] = useState<Filters>(EMPTY);
  const [filters, setFilters] = useState<Filters>(EMPTY);
  const [page, setPage] = useState(0);
  const [logs, setLogs] = useState<AuditRow[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const list = await fetchAuditLogs({
          action: filters.action.trim(),
          entityType: filters.entityType.trim(),
          entityId: filters.entityId.trim(),
          actor: filters.actor.trim(),
          page,
          size: PAGE_SIZE,
        });
        if (active) setLogs(list);
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [filters, page, reloadKey]);

  function change(apply: () => void) {
    apply();
    setIsLoading(true);
    setError(null);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    change(() => {
      setFilters({ ...form });
      setPage(0);
    });
  }

  function clear() {
    change(() => {
      setForm(EMPTY);
      setFilters(EMPTY);
      setPage(0);
    });
  }

  const filtered = Object.values(filters).some((value) => value.trim() !== "");
  const openLog = logs.find((log) => log.id === openId) ?? null;

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Nhật ký thao tác</h1>
        <p className="mt-1.5 text-small text-muted">
          Mọi thay đổi trạng thái được ghi lại kèm người thực hiện, giá trị cũ / mới và lý do.
        </p>
      </div>

      <form
        className="grid grid-cols-[repeat(4,minmax(0,1fr))_auto] items-end gap-3 max-[1100px]:grid-cols-2 max-[560px]:grid-cols-1"
        onSubmit={submit}
      >
        {(
          [
            ["action", "Hành động", "Ví dụ: CONTRACT_SIGNED"],
            ["entityType", "Loại đối tượng", "Ví dụ: RentalContract"],
            ["entityId", "Mã đối tượng", "Ví dụ: 12"],
            ["actor", "Email người thực hiện", "Một phần email"],
          ] as const
        ).map(([key, label, placeholder]) => (
          <div key={key}>
            <label className={FIELD_LABEL} htmlFor={`audit-${key}`}>
              {label}
            </label>
            <input
              id={`audit-${key}`}
              className={INPUT}
              value={form[key]}
              placeholder={placeholder}
              onChange={(event) => setForm({ ...form, [key]: event.target.value })}
            />
          </div>
        ))}
        <div className="flex gap-2">
          <Button type="submit">Lọc</Button>
          {filtered && (
            <Button variant="secondary" onClick={clear}>
              Xoá lọc
            </Button>
          )}
        </div>
      </form>

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải nhật ký…" />
      ) : error ? (
        error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xem nhật ký thao tác"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được nhật ký thao tác"
            description={error.message}
            action={{ label: "Thử lại", onClick: () => change(() => setReloadKey((k) => k + 1)) }}
          />
        )
      ) : logs.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={
            page > 0
              ? "Hết dữ liệu"
              : filtered
                ? "Không có bản ghi khớp bộ lọc"
                : "Chưa có bản ghi nào"
          }
          description={
            filtered ? "Hành động, loại và mã đối tượng phải khớp chính xác." : undefined
          }
          action={
            page > 0
              ? { label: "Về trang đầu", onClick: () => change(() => setPage(0)) }
              : filtered
                ? { label: "Xoá bộ lọc", onClick: clear }
                : undefined
          }
        />
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[820px] border-separate border-spacing-y-1.5 text-small">
              <thead>
                <tr className="bg-ink text-left text-label text-background">
                  <th className="px-3.5 py-2.5 font-semibold">Thời điểm</th>
                  <th className="px-3.5 py-2.5 font-semibold">Hành động</th>
                  <th className="px-3.5 py-2.5 font-semibold">Đối tượng</th>
                  <th className="px-3.5 py-2.5 font-semibold">Người thực hiện</th>
                  <th className="px-3.5 py-2.5 font-semibold">Lý do</th>
                </tr>
              </thead>
              <tbody>
                {logs.map((log) => (
                  <tr
                    key={log.id}
                    className="cursor-pointer bg-surface transition-colors hover:bg-surface-subtle"
                    onClick={() => setOpenId(log.id)}
                  >
                    <td className="px-3.5 py-3 tabular-nums text-muted">
                      {formatDateTime(log.createdAt)}
                    </td>
                    <td className="px-3.5 py-3">
                      <button
                        className="border-0 bg-transparent p-0 text-left font-semibold text-ink hover:underline"
                        onClick={(event) => {
                          event.stopPropagation();
                          setOpenId(log.id);
                        }}
                      >
                        {log.action}
                      </button>
                    </td>
                    <td className="px-3.5 py-3 text-muted">
                      {log.entityType}
                      {log.entityId ? ` #${log.entityId}` : ""}
                    </td>
                    <td className="px-3.5 py-3 text-muted">{log.actorEmail ?? "Hệ thống"}</td>
                    <td className="max-w-[280px] truncate px-3.5 py-3 text-muted">
                      {log.reason ?? "—"}
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
              disabled={logs.length < PAGE_SIZE}
              onClick={() => change(() => setPage((p) => p + 1))}
            >
              Trang sau
            </Button>
          </div>
        </>
      )}

      {openLog && (
        <DetailPanel
          title={openLog.action}
          eyebrow={`${openLog.entityType}${openLog.entityId ? ` #${openLog.entityId}` : ""}`}
          onClose={() => setOpenId(null)}
        >
          <dl className="m-0 grid gap-2 text-small">
            <div className="flex justify-between gap-3">
              <dt className="text-muted">Thời điểm</dt>
              <dd className="m-0 text-right text-ink">{formatDateTime(openLog.createdAt)}</dd>
            </div>
            <div className="flex justify-between gap-3">
              <dt className="text-muted">Người thực hiện</dt>
              <dd className="m-0 text-right text-ink">
                {openLog.actorEmail ??
                  (openLog.actorId !== null ? `#${openLog.actorId}` : "Hệ thống")}
              </dd>
            </div>
            <div className="flex justify-between gap-3">
              <dt className="text-muted">Lý do</dt>
              <dd className="m-0 text-right text-ink">{openLog.reason ?? "—"}</dd>
            </div>
          </dl>
          <div className="mt-6 grid gap-2">
            <SectionTitle>Giá trị cũ</SectionTitle>
            <pre className="m-0 max-h-[260px] overflow-auto whitespace-pre-wrap break-all bg-surface-subtle p-3 font-mono text-[12px] text-ink">
              {pretty(openLog.oldValue)}
            </pre>
          </div>
          <div className="mt-5 grid gap-2">
            <SectionTitle>Giá trị mới</SectionTitle>
            <pre className="m-0 max-h-[260px] overflow-auto whitespace-pre-wrap break-all bg-surface-subtle p-3 font-mono text-[12px] text-ink">
              {pretty(openLog.newValue)}
            </pre>
          </div>
        </DetailPanel>
      )}
    </div>
  );
}
