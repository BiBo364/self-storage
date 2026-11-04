import { CalendarClock, ChevronRight, Search } from "lucide-react";
import { useMemo, useState } from "react";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";
import { fmOrders, type FmOrderStatus } from "../../mocks/orders";

const STATUS: Record<
  FmOrderStatus,
  { label: string; tone: "success" | "warning" | "danger" | "info" | "neutral" }
> = {
  Pending: { label: "Chờ xử lý", tone: "warning" },
  Deposited: { label: "Đã cọc", tone: "info" },
  Scheduled: { label: "Đã đặt lịch", tone: "info" },
  InProgress: { label: "Đang bàn giao", tone: "warning" },
  Done: { label: "Hoàn tất", tone: "success" },
  Canceled: { label: "Đã huỷ", tone: "danger" },
};

const ACTIVE_STATUSES: FmOrderStatus[] = ["Pending", "Deposited", "Scheduled", "InProgress"];

function dateValue(value: string) {
  const [day, month, year] = value.split("/").map(Number);
  return new Date(year, month - 1, day).getTime();
}

export function RentalOrderListPage({ navigate }: { navigate: Navigate }) {
  const [status, setStatus] = useState<"all" | FmOrderStatus>("all");
  const [query, setQuery] = useState("");
  const [openId, setOpenId] = useState<string | null>(null);

  const filtered = useMemo(() => {
    const term = query.trim().toLowerCase();
    return fmOrders
      .filter((order) => status === "all" || order.status === status)
      .filter((order) => {
        if (!term) return true;
        return `${order.code} ${order.customerName} ${order.customerEmail} ${order.unitCode ?? ""}`
          .toLowerCase()
          .includes(term);
      })
      .sort((left, right) => dateValue(left.expiresAt) - dateValue(right.expiresAt));
  }, [status, query]);

  const openOrder = fmOrders.find((order) => order.id === openId) ?? null;
  const activeCount = fmOrders.filter((order) => ACTIVE_STATUSES.includes(order.status)).length;
  const closedCount = fmOrders.length - activeCount;

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Đơn thuê kho</h1>
        <p className="mt-1.5 text-small text-muted">
          Theo dõi toàn bộ đơn của cơ sở và mở chi tiết khi cần xử lý.
        </p>
      </div>

      <DemoNotice tone="info">
        Prototype — dữ liệu minh hoạ; API danh sách đơn thuê chưa có.
      </DemoNotice>

      <div className="grid grid-cols-3 gap-2 max-[760px]:grid-cols-1">
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">
            {String(fmOrders.length).padStart(2, "0")}
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
        <FilterSelect
          label="Trạng thái"
          value={status}
          onChange={(value) => setStatus(value as "all" | FmOrderStatus)}
        >
          <option value="all">Tất cả trạng thái</option>
          {Object.entries(STATUS).map(([value, meta]) => (
            <option key={value} value={value}>
              {meta.label}
            </option>
          ))}
        </FilterSelect>
        <label className="grid gap-1.5">
          <span className="text-label text-muted">Tìm đơn / khách / khoang</span>
          <div className="relative">
            <Search
              size={16}
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted"
            />
            <input
              className="min-h-[42px] w-full border border-border bg-surface pl-9 pr-3 text-small text-ink"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="ORD-..., tên khách, mã khoang..."
            />
          </div>
        </label>
      </div>

      {filtered.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Không có đơn phù hợp"
          description="Thử đổi trạng thái hoặc từ khoá tìm kiếm."
        />
      ) : (
        <div className="grid gap-3">
          {filtered.map((order) => {
            const meta = STATUS[order.status];
            return (
              <article key={order.id} className="border border-border bg-surface p-5">
                <div className="flex flex-wrap items-start justify-between gap-4">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-mono text-label text-muted">{order.code}</span>
                      <StatusTag tone={meta.tone}>{meta.label}</StatusTag>
                      {order.needsReproposal && (
                        <span className="bg-warning-soft px-2 py-1 text-label font-semibold text-warning">
                          Cần đề xuất lại
                        </span>
                      )}
                    </div>
                    <h2 className="mb-0 mt-2 text-section text-ink">{order.customerName}</h2>
                    <p className="mb-0 mt-1 text-small text-muted">
                      {order.unitCode ? `${order.unitCode} · ` : ""}
                      {order.unitType}
                    </p>
                  </div>
                  <div className="text-right max-[760px]:text-left">
                    <span className="block text-label text-muted">{order.facility}</span>
                    <span className="mt-0.5 flex items-center justify-end gap-1 text-label text-muted max-[760px]:justify-start">
                      <CalendarClock size={14} /> Hết hạn {order.expiresAt}
                    </span>
                  </div>
                </div>
                <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
                  <span className="text-label text-muted">Tạo ngày {order.createdAt}</span>
                  <Button size="sm" variant="secondary" onClick={() => setOpenId(order.id)}>
                    Xem chi tiết <ChevronRight size={14} />
                  </Button>
                </div>
              </article>
            );
          })}
        </div>
      )}

      {openOrder && (
        <DetailPanel
          title={openOrder.code}
          eyebrow={openOrder.customerName}
          onClose={() => setOpenId(null)}
          footer={
            openOrder.needsReproposal ? (
              <Button onClick={() => navigate(`/fm/rental-orders/${openOrder.id}/re-propose`)}>
                Đề xuất lại khoang <ChevronRight size={16} />
              </Button>
            ) : (
              <Button variant="secondary" onClick={() => setOpenId(null)}>
                Đóng
              </Button>
            )
          }
        >
          <dl className="grid gap-3 text-small">
            <div className="flex items-center justify-between gap-3">
              <dt className="text-muted">Trạng thái</dt>
              <dd className="m-0">
                <StatusTag tone={STATUS[openOrder.status].tone}>
                  {STATUS[openOrder.status].label}
                </StatusTag>
              </dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt className="text-muted">Khách</dt>
              <dd className="m-0 text-ink">{openOrder.customerName}</dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt className="text-muted">Email</dt>
              <dd className="m-0 text-ink">{openOrder.customerEmail}</dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt className="text-muted">Khoang</dt>
              <dd className="m-0 text-ink">{openOrder.unitCode ?? "Chưa gán"}</dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt className="text-muted">Loại khoang</dt>
              <dd className="m-0 text-ink">{openOrder.unitType}</dd>
            </div>
            <div className="flex items-start justify-between gap-3">
              <dt className="text-muted">Cơ sở</dt>
              <dd className="m-0 text-right text-ink">{openOrder.facility}</dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt className="text-muted">Ngày tạo</dt>
              <dd className="m-0 text-ink">{openOrder.createdAt}</dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt className="text-muted">Hết hạn giữ kho</dt>
              <dd className="m-0 text-ink">{openOrder.expiresAt}</dd>
            </div>
          </dl>
          {openOrder.cancelReason && <DemoNotice tone="error">{openOrder.cancelReason}</DemoNotice>}
        </DetailPanel>
      )}
    </div>
  );
}
