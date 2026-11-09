import { ChevronRight, Search } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { ApiError } from "../../app/api";
import { fetchCurrentFmFacility } from "../../app/fmFacility";
import { fetchFmRentalOrders } from "../../app/fmOrders";
import type { FmRentalOrderSummary, Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const STATUS: Record<
  string,
  { label: string; tone: "success" | "warning" | "danger" | "info" | "neutral" }
> = {
  Pending: { label: "Chờ xử lý", tone: "warning" },
  Deposited: { label: "Đã cọc", tone: "info" },
  Scheduled: { label: "Đã đặt lịch", tone: "info" },
  InProgress: { label: "Đang bàn giao", tone: "warning" },
  Done: { label: "Hoàn tất", tone: "success" },
  Canceled: { label: "Đã huỷ", tone: "danger" },
};

const ACTIVE_STATUSES = ["Pending", "Deposited", "Scheduled", "InProgress"];

function formatDate(value: string | null) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("vi-VN", { dateStyle: "short" }).format(new Date(value));
}

function deepLinkOrderId() {
  const raw = new URLSearchParams(window.location.search).get("orderId");
  const id = raw ? Number(raw) : NaN;
  return Number.isFinite(id) && id > 0 ? id : null;
}

export function RentalOrderListPage({ navigate }: { navigate: Navigate }) {
  const [status, setStatus] = useState("all");
  const [query, setQuery] = useState("");
  const [items, setItems] = useState<FmRentalOrderSummary[]>([]);
  const [openId, setOpenId] = useState<number | null>(deepLinkOrderId);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let active = true;

    (async () => {
      try {
        const facility = await fetchCurrentFmFacility();
        const page = await fetchFmRentalOrders(facility.id, undefined, 0, 100);
        if (!active) return;
        setItems(page.items);
      } catch (caught) {
        if (!active) return;
        setError(
          caught instanceof ApiError
            ? { status: caught.status, message: caught.message }
            : { status: 0, message: "Không thể kết nối tới máy chủ." },
        );
      } finally {
        if (active) setIsLoading(false);
      }
    })();

    return () => {
      active = false;
    };
  }, [reloadKey]);

  const filtered = useMemo(() => {
    const term = query.trim().toLowerCase();
    return items
      .filter((order) => status === "all" || order.status === status)
      .filter((order) => {
        if (!term) return true;
        return `${order.id} ${order.customerName} ${order.customerEmail} ${order.unitCode ?? ""}`
          .toLowerCase()
          .includes(term);
      });
  }, [items, query, status]);

  const openOrder = items.find((order) => order.id === openId) ?? null;
  const activeCount = items.filter((order) => ACTIVE_STATUSES.includes(order.status)).length;
  const closedCount = items.length - activeCount;

  if (isLoading) {
    return <SurfaceState variant="loading" title="Đang tải đơn thuê…" />;
  }

  if (error) {
    if (error.status === 403 || error.status === 404) {
      return (
        <SurfaceState
          variant="forbidden"
          title="Bạn không có quyền xem đơn thuê cơ sở"
          description={error.message}
        />
      );
    }
    return (
      <SurfaceState
        variant="error"
        title="Không tải được danh sách đơn thuê"
        description={error.message}
        action={{
          label: "Thử lại",
          onClick: () => {
            setIsLoading(true);
            setError(null);
            setReloadKey((key) => key + 1);
          },
        }}
      />
    );
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Đơn thuê kho</h1>
        <p className="mt-1.5 text-small text-muted">
          Theo dõi toàn bộ đơn của cơ sở và mở chi tiết khi cần xử lý.
        </p>
      </div>

      <div className="grid grid-cols-3 gap-2 max-[760px]:grid-cols-1">
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">
            {String(items.length).padStart(2, "0")}
          </strong>
          <span className="text-label text-muted">Tổng đơn</span>
        </div>
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">
            {String(activeCount).padStart(2, "0")}
          </strong>
          <span className="text-label text-muted">Đang xử lý</span>
        </div>
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">
            {String(closedCount).padStart(2, "0")}
          </strong>
          <span className="text-label text-muted">Đã đóng</span>
        </div>
      </div>

      <div className="grid grid-cols-2 items-end gap-3 max-[760px]:grid-cols-1">
        <FilterSelect label="Trạng thái" value={status} onChange={setStatus}>
          <option value="all">Tất cả trạng thái</option>
          {Object.entries(STATUS).map(([value, meta]) => (
            <option key={value} value={value}>
              {meta.label}
            </option>
          ))}
        </FilterSelect>
        <label className="grid gap-1.5">
          <span className="text-label font-semibold text-ink">Tìm kiếm</span>
          <span className="relative">
            <Search
              size={15}
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted"
            />
            <input
              className="w-full rounded-[2px] border border-rule-strong bg-surface py-2.5 pl-9 pr-3 text-small text-ink outline-0 focus:border-brand"
              placeholder="Mã đơn, tên, email, khoang…"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </span>
        </label>
      </div>

      {filtered.length === 0 ? (
        <SurfaceState variant="empty" title="Không có đơn phù hợp bộ lọc" />
      ) : (
        <div className="grid gap-2">
          {filtered.map((order) => {
            const meta = STATUS[order.status] ?? { label: order.status, tone: "neutral" as const };
            return (
              <button
                key={order.id}
                className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
                onClick={() => setOpenId(order.id)}
              >
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <StatusTag tone={meta.tone}>{meta.label}</StatusTag>
                    {order.needsRepropose && <StatusTag tone="warning">Cần đề xuất lại</StatusTag>}
                    <strong className="text-small font-semibold text-ink">Đơn #{order.id}</strong>
                  </div>
                  <span className="mt-1.5 block text-label text-muted">
                    {order.customerName} · Khoang {order.unitCode ?? "—"} · Hạn{" "}
                    {formatDate(order.expiresAt)}
                  </span>
                  {order.lastProposal && (
                    <span className="mt-1 block text-label text-muted">
                      Đề xuất gần nhất: {order.lastProposal.unitCode} · {order.lastProposal.status}{" "}
                      · {order.rejectionCount}/{order.maxRejectionCount} từ chối
                    </span>
                  )}
                </div>
                <ChevronRight size={16} className="text-muted" />
              </button>
            );
          })}
        </div>
      )}

      {openOrder && (
        <DetailPanel
          title={`Đơn #${openOrder.id}`}
          eyebrow={`${STATUS[openOrder.status]?.label ?? openOrder.status} · ${openOrder.customerName}`}
          onClose={() => setOpenId(null)}
        >
          <div className="grid gap-2 text-small text-muted">
            <span>{openOrder.customerEmail}</span>
            <span>{openOrder.customerPhone}</span>
            <span>Khoang {openOrder.unitCode ?? "—"}</span>
            <span>Hạn đơn {formatDate(openOrder.expiresAt)}</span>
            {openOrder.cancelReason && <span>Lý do huỷ: {openOrder.cancelReason}</span>}
            {openOrder.lastProposal && (
              <span>
                Đề xuất gần nhất: khoang {openOrder.lastProposal.unitCode} ·{" "}
                {openOrder.lastProposal.status}
                {openOrder.lastProposal.note ? ` — ${openOrder.lastProposal.note}` : ""}
              </span>
            )}
            <span>
              Đã từ chối {openOrder.rejectionCount}/{openOrder.maxRejectionCount} lần
            </span>
          </div>

          {openOrder.needsRepropose && (
            <Button
              className="mt-6 w-full"
              onClick={() => {
                setOpenId(null);
                navigate(`/fm/rental-orders/${openOrder.id}/re-propose`);
              }}
            >
              Đề xuất lại khoang <ChevronRight size={16} />
            </Button>
          )}
          <Button
            variant="secondary"
            className="mt-3 w-full"
            onClick={() => {
              setOpenId(null);
              navigate("/fm/rental-orders");
            }}
          >
            Đóng <ChevronRight size={16} />
          </Button>
        </DetailPanel>
      )}
    </div>
  );
}
