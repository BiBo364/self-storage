import { CalendarClock, ChevronRight, RefreshCw, Search, XCircle } from "lucide-react";
import { useState } from "react";
import { formatDate, formatDateTime, formatMoney, formatTime } from "../../app/format";
import {
  cancelRentalOrder,
  fetchRentalOrder,
  fetchRentalOrders,
  INVOICE_STATUS_LABEL,
  INVOICE_TYPE_LABEL,
  labelOf,
  loadErrorState,
  ORDER_STATUS_LABEL,
  ORDER_STATUS_TONE,
  PROPOSAL_STATUS_LABEL,
  PROPOSAL_STATUS_TONE,
  CONTRACT_STATUS_LABEL,
  toneOf,
  useFmAction,
  useFmLoad,
  type OrderOverview,
} from "../../app/services/fm";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const STATUS_OPTIONS = ["Pending", "Deposited", "Scheduled", "InProgress", "Canceled", "Done"];

const APPOINTMENT_STATUS_LABEL: Record<string, string> = {
  Pending: "Chờ khách đến",
  Done: "Khách đã đến",
  Canceled: "Đã huỷ",
};

const TABLE_COLUMNS = "grid-cols-[80px_1.3fr_1fr_0.8fr_0.9fr_0.9fr_40px]";

export function RentalOrderListPage({ navigate }: { navigate: Navigate }) {
  const [status, setStatus] = useState("");
  const [search, setSearch] = useState("");
  const [openId, setOpenId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const orders = useFmLoad(`orders:${status}`, () => fetchRentalOrders(status || undefined));

  const term = search.trim().toLowerCase();
  const list = (orders.data ?? []).filter(({ order }) => {
    if (!term) return true;
    return [order.id, order.customerName, order.customerEmail, order.customerPhone, order.unitCode]
      .map((value) => String(value ?? "").toLowerCase())
      .some((value) => value.includes(term));
  });
  const openOrder = orders.data?.find((item) => item.order.id === openId) ?? null;

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Đơn thuê kho</h1>
        <p className="mt-1.5 text-small text-muted">
          Theo dõi đơn của {orders.facility ? `cơ sở ${orders.facility.name}` : "cơ sở"} và mở chi
          tiết khi cần xử lý.
        </p>
      </div>

      <div className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)] items-end gap-3 max-[760px]:grid-cols-1">
        <label className="grid gap-1.5">
          <span className="text-label text-muted">Tìm kiếm</span>
          <div className={INPUT_WRAP}>
            <Search size={15} />
            <input
              className={INPUT_ELEMENT}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Mã đơn, tên khách, email, mã khoang…"
            />
          </div>
        </label>
        <FilterSelect
          label="Trạng thái"
          value={status}
          onChange={(value) => {
            setStatus(value);
            setNotice(null);
          }}
        >
          <option value="">Tất cả trạng thái</option>
          {STATUS_OPTIONS.map((value) => (
            <option key={value} value={value}>
              {labelOf(ORDER_STATUS_LABEL, value)}
            </option>
          ))}
        </FilterSelect>
      </div>

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}

      {orders.error ? (
        <SurfaceState
          {...loadErrorState(orders.error, "đơn thuê")}
          action={{ label: "Thử lại", onClick: orders.reload }}
        />
      ) : !orders.data ? (
        <SurfaceState variant="loading" title="Đang tải đơn thuê…" />
      ) : list.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Không có đơn thuê phù hợp"
          description="Thử đổi trạng thái hoặc xoá từ khoá tìm kiếm."
        />
      ) : (
        <div className="overflow-x-auto" aria-busy={orders.loading}>
          <div className={`min-w-[900px] ${orders.loading ? "opacity-60" : ""}`}>
            <div
              className={`grid ${TABLE_COLUMNS} gap-4 bg-[var(--dark)] px-3.5 py-2.5 text-label text-[rgba(233,234,230,.62)]`}
            >
              <span>Mã đơn</span>
              <span>Khách hàng</span>
              <span>Khoang</span>
              <span>Hạn đơn</span>
              <span>Trạng thái</span>
              <span>Việc cần làm</span>
              <span />
            </div>
            <div className="mt-1.5 grid gap-1.5">
              {list.map((item) => (
                <OrderRow key={item.order.id} item={item} onOpen={() => setOpenId(item.order.id)} />
              ))}
            </div>
            <p className="mt-3 text-label text-muted">{list.length} đơn</p>
          </div>
        </div>
      )}

      {openOrder && (
        <OrderPanel
          item={openOrder}
          navigate={navigate}
          onClose={() => setOpenId(null)}
          onCanceled={(message) => {
            setOpenId(null);
            setNotice(message);
            orders.reload();
          }}
        />
      )}
    </div>
  );
}

const ACTION_LABEL: Record<string, string> = {
  REPROPOSE: "Đề xuất lại khoang",
  ASSIGN_STAFF: "Phân công FS",
  CANCEL: "Có thể huỷ",
};

function OrderRow({ item, onOpen }: { item: OrderOverview; onOpen: () => void }) {
  const { order, availableActions } = item;
  const todo = availableActions.filter((value) => value !== "CANCEL");
  return (
    <button
      className={`grid ${TABLE_COLUMNS} items-center gap-4 bg-surface px-3.5 py-3 text-left transition-colors hover:bg-surface-subtle`}
      onClick={onOpen}
    >
      <span className="text-small font-semibold text-ink tabular-nums">#{order.id}</span>
      <span className="min-w-0">
        <strong className="block truncate text-small font-semibold text-ink">
          {order.customerName ?? "—"}
        </strong>
        <span className="mt-0.5 block truncate text-label text-muted">
          {order.customerEmail ?? "—"}
        </span>
      </span>
      <span className="text-small text-ink">
        {order.unitCode ?? "Chưa gán"}
        <span className="mt-0.5 block text-label text-muted">{order.unitTypeName ?? "—"}</span>
      </span>
      <span className="text-label text-ink tabular-nums">{formatDate(order.expiresAt)}</span>
      <StatusTag tone={toneOf(ORDER_STATUS_TONE, order.status)}>
        {labelOf(ORDER_STATUS_LABEL, order.status)}
      </StatusTag>
      <span className="text-label text-muted">
        {todo.length === 0 ? "—" : todo.map((value) => ACTION_LABEL[value] ?? value).join(", ")}
      </span>
      <ChevronRight size={16} className="justify-self-end text-muted" />
    </button>
  );
}

function OrderPanel({
  item,
  navigate,
  onClose,
  onCanceled,
}: {
  item: OrderOverview;
  navigate: Navigate;
  onClose: () => void;
  onCanceled: (message: string) => void;
}) {
  const { order, availableActions } = item;
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState("");
  const action = useFmAction();
  const detail = useFmLoad(`order-detail:${order.id}`, () => fetchRentalOrder(order.id));

  async function cancel() {
    const outcome = await action.run("cancel", () => cancelRentalOrder(order.id, reason));
    if (outcome.ok) onCanceled(`Đã huỷ đơn #${order.id}: ${outcome.value.cancelReason}`);
  }

  return (
    <DetailPanel
      title={`Đơn #${order.id}`}
      eyebrow={labelOf(ORDER_STATUS_LABEL, order.status)}
      onClose={onClose}
    >
      <div className="grid gap-5">
        <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-small">
          <Field label="Khách hàng" value={order.customerName} />
          <Field
            label="Liên hệ"
            value={[order.customerPhone, order.customerEmail].filter(Boolean).join(" · ")}
          />
          <Field label="Loại khoang" value={order.unitTypeName} />
          <Field label="Khoang" value={order.unitCode ?? "Chưa gán"} />
          <Field label="Bắt đầu thuê" value={formatDate(order.startDate)} />
          <Field label="Thời hạn" value={`${order.period} tháng`} />
          <Field
            label="Giá thuê"
            value={order.monthlyPrice === null ? "—" : `${formatMoney(order.monthlyPrice)}/tháng`}
          />
          <Field label="Hạn đơn" value={formatDateTime(order.expiresAt)} />
        </dl>

        {order.cancelReason && (
          <DemoNotice tone="error">Lý do huỷ: {order.cancelReason}</DemoNotice>
        )}

        {order.invoiceCode && (
          <section>
            <SectionTitle>Hoá đơn gần nhất</SectionTitle>
            <p className="mt-2 text-small text-ink">
              {order.invoiceCode} · {labelOf(INVOICE_TYPE_LABEL, order.invoiceType)} ·{" "}
              {formatMoney(order.invoiceAmount)}
            </p>
            <p className="mt-0.5 text-label text-muted">
              {labelOf(INVOICE_STATUS_LABEL, order.invoiceStatus)} · hạn{" "}
              {formatDateTime(order.invoiceDueDate)}
            </p>
          </section>
        )}

        {order.appointmentId && (
          <section>
            <SectionTitle>Lịch hẹn gần nhất</SectionTitle>
            <p className="mt-2 flex items-center gap-2 text-small text-ink">
              <CalendarClock size={14} /> {formatDate(order.appointmentDate)} ·{" "}
              {formatTime(order.appointmentStart)}–{formatTime(order.appointmentEnd)}
            </p>
            <p className="mt-0.5 text-label text-muted">
              {labelOf(APPOINTMENT_STATUS_LABEL, order.appointmentStatus)} ·{" "}
              {order.staffId ? `FS #${order.staffId}` : "Chưa phân công FS"}
            </p>
          </section>
        )}

        {order.contractCode && (
          <section>
            <SectionTitle>Hợp đồng</SectionTitle>
            <p className="mt-2 text-small text-ink">
              {order.contractCode} · {labelOf(CONTRACT_STATUS_LABEL, order.contractStatus)}
            </p>
            <p className="mt-0.5 text-label text-muted">
              {formatDate(order.contractStartDate)} – {formatDate(order.contractEndDate)}
            </p>
          </section>
        )}

        <section>
          <SectionTitle>Lịch sử đề xuất khoang</SectionTitle>
          {detail.error ? (
            <DemoNotice tone="error">{detail.error.message}</DemoNotice>
          ) : !detail.data ? (
            <DemoNotice tone="pending">Đang tải lịch sử đề xuất…</DemoNotice>
          ) : detail.data.proposals.length === 0 ? (
            <p className="mt-2 text-label text-muted">Chưa có đề xuất nào.</p>
          ) : (
            <div className="mt-2 grid gap-1.5">
              <p className="text-label text-muted">
                Khách đã từ chối {detail.data.rejectionCount}/{detail.data.maxRejectionCount} lần
              </p>
              {detail.data.proposals.map((proposal) => (
                <div key={proposal.id} className="bg-surface-subtle p-3">
                  <div className="flex items-center justify-between gap-3">
                    <strong className="text-small text-ink">{proposal.unitCode}</strong>
                    <StatusTag tone={toneOf(PROPOSAL_STATUS_TONE, proposal.status)}>
                      {labelOf(PROPOSAL_STATUS_LABEL, proposal.status)}
                    </StatusTag>
                  </div>
                  <span className="mt-1 block text-label text-muted">
                    {formatDateTime(proposal.createdAt)}
                    {proposal.note ? ` · ${proposal.note}` : ""}
                  </span>
                </div>
              ))}
            </div>
          )}
        </section>

        {action.error && <DemoNotice tone="error">{action.error}</DemoNotice>}

        <div className="flex flex-wrap gap-2.5">
          {availableActions.includes("REPROPOSE") && (
            <Button onClick={() => navigate(`/fm/rental-orders/${order.id}/re-propose`)}>
              <RefreshCw size={16} /> Đề xuất lại khoang
            </Button>
          )}
          {availableActions.includes("ASSIGN_STAFF") && (
            <Button
              variant={availableActions.includes("REPROPOSE") ? "secondary" : "primary"}
              onClick={() =>
                navigate(
                  order.appointmentDate
                    ? `/fm/appointments?date=${order.appointmentDate}`
                    : "/fm/appointments",
                )
              }
            >
              <CalendarClock size={16} /> Phân công FS
            </Button>
          )}
          {availableActions.includes("CANCEL") && !cancelling && (
            <Button variant="danger" onClick={() => setCancelling(true)}>
              <XCircle size={16} /> Huỷ đơn
            </Button>
          )}
        </div>

        {cancelling && (
          <div className="bg-surface-subtle p-4">
            <label className="grid gap-1.5 text-label text-muted" htmlFor="cancel-reason">
              Lý do huỷ (để trống: “Khách không còn nhu cầu”)
              <div className={INPUT_WRAP}>
                <textarea
                  id="cancel-reason"
                  className={`${INPUT_ELEMENT} min-h-[72px] py-2`}
                  value={reason}
                  maxLength={500}
                  onChange={(event) => setReason(event.target.value)}
                />
              </div>
            </label>
            <p className="mt-2 text-label text-muted">
              Huỷ đơn sẽ huỷ đề xuất, hoá đơn chưa thanh toán, lịch hẹn và biên bản đang mở của đơn.
            </p>
            <div className="mt-3 flex gap-2.5">
              <Button variant="danger" pending={action.pending === "cancel"} onClick={cancel}>
                Xác nhận huỷ đơn
              </Button>
              <Button variant="secondary" onClick={() => setCancelling(false)}>
                Quay lại
              </Button>
            </div>
          </div>
        )}
      </div>
    </DetailPanel>
  );
}

function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div className="min-w-0">
      <dt className="text-label text-muted">{label}</dt>
      <dd className="m-0 break-words text-ink">{value || "—"}</dd>
    </div>
  );
}
