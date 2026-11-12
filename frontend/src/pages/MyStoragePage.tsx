import { AlertTriangle, ArrowRight, Plus } from "lucide-react";
import { useState } from "react";
import type { Navigate } from "../app/types";
import { formatDate, formatDateTime, formatMoney, formatTime } from "../app/format";
import { errorMessage } from "../app/http";
import {
  cancelMyOrder,
  fetchMyHandovers,
  fetchMyOrders,
  fetchRentals,
  HANDOVER_STEP_LABEL,
  INVOICE_STATUS,
  INVOICE_TYPE_LABEL,
  ORDER_STATUS,
  useResource,
  type OrderOverview,
  type RentalView,
} from "../app/services/customer";
import { UnitLocationCard } from "../components/domain/UnitLocationCard";
import { Button } from "../components/ui/Button";
import { ConfirmDialog } from "../components/ui/ConfirmDialog";
import { DemoNotice } from "../components/ui/DemoNotice";
import { SectionTitle } from "../components/ui/SectionTitle";
import { StatusTag } from "../components/ui/StatusTag";
import { SurfaceState } from "../components/ui/SurfaceState";

function Skeleton({ count, height }: { count: number; height: string }) {
  return (
    <div className="grid gap-2" role="status" aria-label="Đang tải">
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className={`${height} animate-pulse bg-surface`} />
      ))}
    </div>
  );
}

export function MyStoragePage({ navigate }: { navigate: Navigate }) {
  const orders = useResource(fetchMyOrders);
  const rentals = useResource(fetchRentals);
  const handovers = useResource(fetchMyHandovers);
  const [showClosed, setShowClosed] = useState(false);
  const [cancelTarget, setCancelTarget] = useState<OrderOverview | null>(null);
  const [canceling, setCanceling] = useState(false);
  const [notice, setNotice] = useState<{ tone: "success" | "error"; message: string } | null>(null);

  const orderList = orders.data ?? [];
  const openOrders = orderList.filter(
    ({ order }) => order.status !== "Done" && order.status !== "Canceled",
  );
  const closedOrders = orderList.filter(
    ({ order }) => order.status === "Done" || order.status === "Canceled",
  );
  const rentalList = rentals.data ?? [];
  const openHandovers = (handovers.data ?? []).filter((item) => item.result === "IN_PROGRESS");
  const proposalTasks = orderList.filter((item) =>
    item.availableActions.includes("RESPOND_PROPOSAL"),
  ).length;
  const payTasks = orderList.filter((item) => item.availableActions.includes("PAY_INVOICE")).length;

  async function confirmCancel() {
    if (!cancelTarget) return;
    setCanceling(true);
    try {
      await cancelMyOrder(cancelTarget.order.id);
      setNotice({ tone: "success", message: `Đã huỷ đơn #${cancelTarget.order.id}.` });
      setCancelTarget(null);
      orders.reload();
      handovers.reload();
    } catch (caught) {
      setNotice({ tone: "error", message: errorMessage(caught) });
      setCancelTarget(null);
      orders.reload();
    } finally {
      setCanceling(false);
    }
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Kho của tôi</h1>
          <p className="mt-1.5 text-small text-muted">
            Theo dõi đơn thuê, hợp đồng và việc cần làm tiếp theo.
          </p>
        </div>
        <Button onClick={() => navigate("/rental-requests/new")}>
          Thuê thêm kho <Plus size={16} />
        </Button>
      </div>

      <div className="grid grid-cols-3 gap-2 max-[760px]:grid-cols-1">
        <Stat value={rentals.data ? rentalList.length : null} label="Khoang đang thuê" />
        <Stat value={orders.data ? openOrders.length : null} label="Đơn đang xử lý" />
        <Stat value={handovers.data ? openHandovers.length : null} label="Phiên bàn giao đang mở" />
      </div>

      {notice && <DemoNotice tone={notice.tone}>{notice.message}</DemoNotice>}

      <div className="grid grid-cols-[minmax(0,1fr)_312px] items-start gap-8 max-[1100px]:grid-cols-1">
        <div className="grid gap-8">
          <section>
            <div className="mb-2">
              <SectionTitle
                action={
                  rentals.data ? (
                    <span className="text-label text-muted">{rentalList.length} hợp đồng</span>
                  ) : undefined
                }
              >
                Hợp đồng đang hoạt động
              </SectionTitle>
            </div>
            {rentals.loading && !rentals.data ? (
              <Skeleton count={2} height="h-[150px]" />
            ) : rentals.error && !rentals.data ? (
              <SurfaceState
                variant="error"
                title="Không tải được hợp đồng"
                description={rentals.error}
                action={{ label: "Thử lại", onClick: rentals.reload }}
              />
            ) : rentalList.length === 0 ? (
              <SurfaceState
                variant="empty"
                title="Chưa có hợp đồng đang hoạt động"
                description="Hợp đồng xuất hiện ở đây sau khi bạn hoàn tất bàn giao khoang."
              />
            ) : (
              <div className="grid gap-2">
                {rentalList.map((rental) => (
                  <RentalCard key={rental.contractId} rental={rental} navigate={navigate} />
                ))}
              </div>
            )}
          </section>

          <section>
            <div className="mb-2">
              <SectionTitle
                action={
                  closedOrders.length > 0 ? (
                    <button
                      className="border-0 bg-transparent p-0 text-label font-semibold text-accent hover:text-ink"
                      onClick={() => setShowClosed((value) => !value)}
                    >
                      {showClosed ? "Ẩn đơn đã đóng" : `Xem ${closedOrders.length} đơn đã đóng`}
                    </button>
                  ) : undefined
                }
              >
                Đơn thuê
              </SectionTitle>
            </div>
            {orders.loading && !orders.data ? (
              <Skeleton count={2} height="h-[140px]" />
            ) : orders.error && !orders.data ? (
              <SurfaceState
                variant="error"
                title="Không tải được đơn thuê"
                description={orders.error}
                action={{ label: "Thử lại", onClick: orders.reload }}
              />
            ) : orderList.length === 0 ? (
              <SurfaceState
                variant="empty"
                title="Chưa có đơn thuê nào"
                description="Gửi nhu cầu lưu trữ để cơ sở đề xuất khoang phù hợp cho bạn."
                action={{ label: "Gửi yêu cầu", onClick: () => navigate("/rental-requests/new") }}
              />
            ) : (
              <div className="grid gap-2">
                {openOrders.length === 0 && !showClosed && (
                  <p className="bg-surface p-4 text-small text-muted">
                    Không có đơn nào đang xử lý.
                  </p>
                )}
                {(showClosed ? [...openOrders, ...closedOrders] : openOrders).map((item) => (
                  <OrderCard
                    key={item.order.id}
                    item={item}
                    navigate={navigate}
                    onCancel={() => {
                      setNotice(null);
                      setCancelTarget(item);
                    }}
                  />
                ))}
              </div>
            )}
          </section>
        </div>

        <aside className="grid gap-4">
          <div className="bg-surface-subtle p-4">
            <div className="mb-3">
              <SectionTitle>Việc cần làm</SectionTitle>
            </div>
            <div className="grid gap-2">
              {handovers.loading && !handovers.data ? (
                <div className="h-14 animate-pulse bg-surface" />
              ) : handovers.error && !handovers.data ? (
                <p className="bg-surface p-3 text-label text-danger">
                  Không tải được phiên bàn giao: {handovers.error}
                </p>
              ) : (
                openHandovers.map((handover) => (
                  <TaskLink
                    key={handover.id}
                    title={`Bàn giao khoang ${handover.unitCode ?? ""}`.trim()}
                    detail={HANDOVER_STEP_LABEL[handover.step] ?? handover.step}
                    onClick={() => navigate(`/handovers/${handover.id}`)}
                  />
                ))
              )}
              {proposalTasks > 0 && (
                <TaskLink
                  title="Xác nhận đề xuất khoang"
                  detail={`${proposalTasks} đơn đang chờ bạn phản hồi.`}
                  onClick={() => navigate("/proposals")}
                />
              )}
              {payTasks > 0 && (
                <TaskLink
                  title="Thanh toán hoá đơn"
                  detail={`${payTasks} đơn có hoá đơn chưa thanh toán.`}
                  onClick={() => navigate("/invoices")}
                />
              )}
              {handovers.data &&
                orders.data &&
                openHandovers.length === 0 &&
                proposalTasks === 0 &&
                payTasks === 0 && (
                  <p className="bg-surface p-3 text-label text-muted">
                    Không có việc nào cần bạn xử lý.
                  </p>
                )}
            </div>
          </div>

          <div className="bg-[var(--dark)] p-4 text-[var(--on-dark-ink)]">
            <h2 className="text-small font-semibold">Hỗ trợ</h2>
            <p className="mt-1.5 text-small text-[rgba(233,234,230,.7)]">
              Báo mất chìa, sự cố khoang, mã khoá số hoặc thắc mắc hoá đơn.
            </p>
            <Button variant="onDark" className="mt-4" onClick={() => navigate("/support")}>
              Gửi yêu cầu hỗ trợ <ArrowRight size={16} />
            </Button>
          </div>
        </aside>
      </div>

      {cancelTarget && (
        <ConfirmDialog
          title={`Huỷ đơn #${cancelTarget.order.id}?`}
          description="Đơn, đề xuất, hoá đơn chưa thanh toán và lịch hẹn liên quan sẽ bị huỷ. Nếu bạn đã đặt cọc, khoản cọc sẽ không được hoàn theo chính sách huỷ do khách chủ động."
          confirmLabel="Huỷ đơn"
          cancelLabel="Giữ đơn"
          isPending={canceling}
          onConfirm={confirmCancel}
          onCancel={() => setCancelTarget(null)}
        />
      )}
    </div>
  );
}

function Stat({ value, label }: { value: number | null; label: string }) {
  return (
    <div className="bg-surface p-4">
      <strong className="block text-stat text-ink">
        {value === null ? "—" : String(value).padStart(2, "0")}
      </strong>
      <span className="text-label text-muted">{label}</span>
    </div>
  );
}

function TaskLink({
  title,
  detail,
  onClick,
}: {
  title: string;
  detail: string;
  onClick: () => void;
}) {
  return (
    <button
      className="flex items-start justify-between gap-3 bg-surface p-3 text-left hover:bg-surface-subtle"
      onClick={onClick}
    >
      <span>
        <strong className="block text-small font-semibold text-ink">{title}</strong>
        <span className="mt-0.5 block text-label text-muted">{detail}</span>
      </span>
      <ArrowRight size={16} className="mt-0.5 shrink-0 text-muted" />
    </button>
  );
}

function RentalCard({ rental, navigate }: { rental: RentalView; navigate: Navigate }) {
  const unpaid = rental.invoices.filter((invoice) => invoice.status === "Unpaid");
  const unpaidTotal = unpaid.reduce((sum, invoice) => sum + Number(invoice.amount), 0);
  return (
    <article className="bg-surface p-5">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex items-baseline gap-2">
            <strong className="whitespace-nowrap text-stat text-ink">{rental.unitCode}</strong>
            <span className="text-label text-muted">{rental.contractCode}</span>
          </div>
          <span className="mt-1 block text-label text-muted">
            {rental.overdue
              ? `Đã quá hạn ${Math.abs(rental.daysLeft)} ngày`
              : `Còn ${rental.daysLeft} ngày`}
          </span>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-2">
          <StatusTag tone={rental.overdue ? "danger" : "success"}>
            {rental.overdue ? "Quá hạn" : "Đang thuê"}
          </StatusTag>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => navigate(`/my-storage/${rental.contractId}`)}
          >
            Xem
          </Button>
        </div>
      </div>
      <div className="mt-4">
        <UnitLocationCard facilityId={rental.facilityId} unitCode={rental.unitCode} compact />
      </div>
      <dl className="mt-4 grid grid-cols-2 gap-4 max-[760px]:grid-cols-1">
        <div>
          <dt className="text-label text-muted">Thời hạn</dt>
          <dd className="m-0 mt-1 whitespace-nowrap text-small text-ink">
            {formatDate(rental.startDate)} → {formatDate(rental.endDate)}
          </dd>
        </div>
        <div>
          <dt className="text-label text-muted">Giá thuê</dt>
          <dd className="m-0 mt-1 text-small font-semibold text-ink">
            {formatMoney(rental.monthlyPrice)} / tháng
          </dd>
        </div>
      </dl>
      {unpaid.length > 0 && (
        <div className="mt-4 flex items-center justify-between gap-4 bg-warning-soft px-3 py-2.5 text-warning">
          <span className="flex items-center gap-2 text-small font-semibold">
            <AlertTriangle size={16} /> {unpaid.length} hoá đơn chưa thanh toán ·{" "}
            {formatMoney(unpaidTotal)}
          </span>
          <button
            className="inline-flex shrink-0 items-center gap-1 border-0 bg-transparent p-0 text-label font-bold hover:underline"
            onClick={() => navigate(`/my-storage/${rental.contractId}`)}
          >
            Thanh toán <ArrowRight size={14} />
          </button>
        </div>
      )}
      {rental.returnRequest && (
        <p className="mt-3 text-label text-muted">
          Đã gửi yêu cầu trả kho ngày {formatDate(rental.returnRequest.preferredDate)}.
        </p>
      )}
    </article>
  );
}

function OrderCard({
  item,
  navigate,
  onCancel,
}: {
  item: OrderOverview;
  navigate: Navigate;
  onCancel: () => void;
}) {
  const { order, availableActions } = item;
  const status = ORDER_STATUS[order.status] ?? { label: order.status, tone: "neutral" as const };
  const actions = new Set(availableActions);

  return (
    <article className="bg-surface p-5">
      <div className="flex items-start justify-between gap-4 max-[760px]:flex-col">
        <div className="min-w-0">
          <span className="text-label text-muted">
            Đơn #{order.id}
            {order.unitTypeName ? ` · ${order.unitTypeName}` : ""}
            {order.unitCode ? ` · Khoang ${order.unitCode}` : ""}
          </span>
          <h3 className="mt-1 text-section text-ink">{order.facilityName}</h3>
          <p className="mt-0.5 text-label text-muted">
            Bắt đầu {formatDate(order.startDate)} · {order.period} tháng
            {order.monthlyPrice !== null ? ` · ${formatMoney(order.monthlyPrice)} / tháng` : ""}
          </p>
        </div>
        <StatusTag tone={status.tone}>{status.label}</StatusTag>
      </div>

      <dl className="mt-4 grid grid-cols-2 gap-3 text-small max-[760px]:grid-cols-1">
        {order.invoiceId !== null && (
          <div>
            <dt className="text-label text-muted">
              Hoá đơn {INVOICE_TYPE_LABEL[order.invoiceType ?? ""] ?? order.invoiceType ?? ""}
            </dt>
            <dd className="m-0 mt-0.5 text-ink">
              {formatMoney(order.invoiceAmount)} ·{" "}
              {INVOICE_STATUS[order.invoiceStatus ?? ""]?.label ?? order.invoiceStatus}
              {order.invoiceStatus === "Unpaid" && order.invoiceDueDate
                ? ` · hạn ${formatDateTime(order.invoiceDueDate)}`
                : ""}
            </dd>
          </div>
        )}
        {order.appointmentId !== null && (
          <div>
            <dt className="text-label text-muted">Lịch hẹn check-in</dt>
            <dd className="m-0 mt-0.5 text-ink">
              {formatDate(order.appointmentDate)} · {formatTime(order.appointmentStart)} –{" "}
              {formatTime(order.appointmentEnd)}
            </dd>
          </div>
        )}
        {order.status !== "Done" && order.status !== "Canceled" && order.expiresAt && (
          <div>
            <dt className="text-label text-muted">Hạn giữ đơn</dt>
            <dd className="m-0 mt-0.5 text-ink">{formatDateTime(order.expiresAt)}</dd>
          </div>
        )}
        {order.cancelReason && (
          <div>
            <dt className="text-label text-muted">Lý do huỷ</dt>
            <dd className="m-0 mt-0.5 text-ink">{order.cancelReason}</dd>
          </div>
        )}
      </dl>

      {availableActions.length > 0 && (
        <div className="mt-4 flex flex-wrap items-center gap-2">
          {actions.has("RESPOND_PROPOSAL") && (
            <Button size="sm" onClick={() => navigate("/proposals")}>
              Xem đề xuất khoang
            </Button>
          )}
          {actions.has("PAY_INVOICE") && (
            <Button size="sm" onClick={() => navigate("/invoices")}>
              Thanh toán hoá đơn
            </Button>
          )}
          {actions.has("BOOK_CHECKIN") && (
            <Button size="sm" onClick={() => navigate(`/appointments/new?orderId=${order.id}`)}>
              Đặt lịch check-in
            </Button>
          )}
          {actions.has("HANDOVER") && order.handoverId !== null && (
            <Button size="sm" onClick={() => navigate(`/handovers/${order.handoverId}`)}>
              Tiếp tục bàn giao
            </Button>
          )}
          {actions.has("VIEW_CONTRACT") && order.contractId !== null && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => navigate(`/my-storage/${order.contractId}`)}
            >
              Xem hợp đồng
            </Button>
          )}
          {actions.has("CANCEL") && (
            <Button variant="danger" size="sm" onClick={onCancel}>
              Huỷ đơn
            </Button>
          )}
        </div>
      )}
    </article>
  );
}
