import { ChevronRight, ClipboardCheck, FileText, Undo2 } from "lucide-react";
import { useState } from "react";
import { formatDate, formatDateTime, formatMoney } from "../app/format";
import { PAGE_CONTAINER } from "../app/layout";
import {
  CHECKOUT_RESULT_LABEL,
  HANDOVER_STEP_LABEL,
  fetchCheckout,
  fetchMyHandovers,
  fetchMyOrders,
  fetchMyReturnRequests,
  useResource,
  type CheckoutView,
  type OrderOverviewRow,
} from "../app/services/customer";
import type { Navigate } from "../app/types";
import { DetailPanel } from "../components/ui/DetailPanel";
import { StatusTag } from "../components/ui/StatusTag";
import { SurfaceState } from "../components/ui/SurfaceState";

type Tab = "contracts" | "handovers" | "returns";
type Tone = "success" | "warning" | "danger" | "info" | "neutral";

const TABS: { key: Tab; label: string }[] = [
  { key: "contracts", label: "Hợp đồng" },
  { key: "handovers", label: "Bàn giao" },
  { key: "returns", label: "Trả kho" },
];

const CONTRACT_STATUS_LABEL: Record<string, string> = {
  Draft: "Nháp",
  Signed: "Đã ký",
  Active: "Đang hiệu lực",
  Ended: "Đã kết thúc",
  Canceled: "Đã huỷ",
};

const CONTRACT_STATUS_TONE: Record<string, Tone> = {
  Draft: "neutral",
  Signed: "info",
  Active: "success",
  Ended: "neutral",
  Canceled: "danger",
};

const HANDOVER_RESULT_LABEL: Record<string, string> = {
  IN_PROGRESS: "Đang thực hiện",
  COMPLETED: "Đã hoàn tất",
  REJECTED: "Khách từ chối khoang",
  CANCELED: "Đã huỷ",
};

const RETURN_STATUS_LABEL: Record<string, string> = {
  Pending: "Chờ phân công",
  Assigned: "Đã có lịch",
  Completed: "Đã trả kho",
  Canceled: "Đã huỷ",
};

const RETURN_TONE: Record<string, Tone> = {
  Pending: "warning",
  Assigned: "info",
  Completed: "success",
  Canceled: "neutral",
};

/**
 * Route `/records` — lịch sử giấy tờ của khách: hợp đồng (mọi trạng thái, lấy từ đơn thuê vì
 * `/api/customer/rentals` chỉ trả hợp đồng `Active`), biên bản bàn giao và biên bản trả kho.
 */
export function RecordsPage({ navigate }: { navigate: Navigate }) {
  const [tab, setTab] = useState<Tab>("contracts");
  const orders = useResource(fetchMyOrders);
  const handovers = useResource(fetchMyHandovers);
  const returns = useResource(fetchMyReturnRequests);
  const [openReturnId, setOpenReturnId] = useState<number | null>(null);
  const [checkout, setCheckout] = useState<CheckoutView | null>(null);
  const [checkoutError, setCheckoutError] = useState<string | null>(null);

  // Mỗi hợp đồng xuất hiện một lần, giữ dòng mới nhất (API trả đơn mới nhất trước).
  const contracts = (orders.data ?? [])
    .map((row) => row.order)
    .reduce<OrderOverviewRow[]>((acc, order) => {
      if (order.contractId === null) return acc;
      if (!acc.some((item) => item.contractId === order.contractId)) acc.push(order);
      return acc;
    }, []);

  async function openReturn(id: number) {
    setOpenReturnId(id);
    setCheckout(null);
    setCheckoutError(null);
    try {
      setCheckout(await fetchCheckout(id));
    } catch (caught) {
      setCheckoutError(
        caught instanceof Error ? caught.message : "Không tải được biên bản trả kho",
      );
    }
  }

  return (
    <div className={`${PAGE_CONTAINER} py-10`}>
      <div className="border-b border-rule pb-6">
        <span className="label text-muted">Giấy tờ của tôi</span>
        <h1 className="mt-2 text-title text-ink">Hợp đồng & biên bản</h1>
        <p className="mt-2 max-w-[620px] text-small text-muted">
          Hợp đồng thuê kho, biên bản bàn giao khoang và biên bản trả kho — xem lại bất cứ lúc nào.
        </p>
      </div>

      <div className="mt-6 flex gap-2 border-b border-rule" role="tablist" aria-label="Giấy tờ">
        {TABS.map(({ key, label }) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={tab === key}
            className={`border-b-2 px-3 py-2 text-small font-semibold ${
              tab === key
                ? "border-accent text-accent"
                : "border-transparent text-muted hover:text-ink"
            }`}
            onClick={() => setTab(key)}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="mt-6 grid gap-2">
        {tab === "contracts" &&
          (orders.error ? (
            <SurfaceState
              variant="error"
              title="Không tải được hợp đồng"
              description={orders.error}
              action={{ label: "Thử lại", onClick: orders.reload }}
            />
          ) : !orders.data ? (
            <SurfaceState variant="loading" title="Đang tải hợp đồng…" />
          ) : contracts.length === 0 ? (
            <SurfaceState
              variant="empty"
              title="Chưa có hợp đồng nào"
              description="Hợp đồng được lập khi bạn đến cơ sở nhận khoang và đồng ý hiện trạng."
              action={{ label: "Xem kho có sẵn", onClick: () => navigate("/units") }}
            />
          ) : (
            contracts.map((order) => (
              <button
                key={order.contractId}
                type="button"
                className="flex flex-wrap items-center justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
                onClick={() => navigate(`/my-storage/${order.contractId}`)}
              >
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <FileText size={15} className="text-muted" />
                    <strong className="text-small font-semibold text-ink">
                      {order.contractCode ?? `Hợp đồng #${order.contractId}`}
                    </strong>
                    <StatusTag tone={CONTRACT_STATUS_TONE[order.contractStatus ?? ""] ?? "neutral"}>
                      {CONTRACT_STATUS_LABEL[order.contractStatus ?? ""] ??
                        order.contractStatus ??
                        "—"}
                    </StatusTag>
                  </div>
                  <span className="mt-1 block text-label text-muted">
                    Khoang {order.unitCode ?? "—"} · {order.facilityName}
                    {order.contractStartDate && order.contractEndDate
                      ? ` · ${formatDate(order.contractStartDate)} → ${formatDate(order.contractEndDate)}`
                      : ""}
                    {order.monthlyPrice !== null
                      ? ` · ${formatMoney(order.monthlyPrice)}/tháng`
                      : ""}
                  </span>
                </div>
                <ChevronRight size={16} className="text-muted" />
              </button>
            ))
          ))}

        {tab === "handovers" &&
          (handovers.error ? (
            <SurfaceState
              variant="error"
              title="Không tải được biên bản bàn giao"
              description={handovers.error}
              action={{ label: "Thử lại", onClick: handovers.reload }}
            />
          ) : !handovers.data ? (
            <SurfaceState variant="loading" title="Đang tải biên bản bàn giao…" />
          ) : handovers.data.length === 0 ? (
            <SurfaceState
              variant="empty"
              title="Chưa có biên bản bàn giao"
              description="Biên bản được mở khi bạn đặt lịch check-in sau khi đặt cọc."
            />
          ) : (
            handovers.data.map((handover) => (
              <button
                key={handover.id}
                type="button"
                className="flex flex-wrap items-center justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
                onClick={() => navigate(`/handovers/${handover.id}`)}
              >
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <ClipboardCheck size={15} className="text-muted" />
                    <strong className="text-small font-semibold text-ink">
                      Bàn giao khoang {handover.unitCode ?? "—"}
                    </strong>
                    <StatusTag tone={handover.result === "COMPLETED" ? "success" : "info"}>
                      {HANDOVER_RESULT_LABEL[handover.result] ?? handover.result}
                    </StatusTag>
                  </div>
                  <span className="mt-1 block text-label text-muted">
                    {handover.facilityName}
                    {handover.appointmentDate
                      ? ` · hẹn ${formatDate(handover.appointmentDate)}`
                      : ""}
                    {` · ${HANDOVER_STEP_LABEL[handover.step] ?? handover.step}`}
                  </span>
                </div>
                <ChevronRight size={16} className="text-muted" />
              </button>
            ))
          ))}

        {tab === "returns" &&
          (returns.error ? (
            <SurfaceState
              variant="error"
              title="Không tải được biên bản trả kho"
              description={returns.error}
              action={{ label: "Thử lại", onClick: returns.reload }}
            />
          ) : !returns.data ? (
            <SurfaceState variant="loading" title="Đang tải biên bản trả kho…" />
          ) : returns.data.length === 0 ? (
            <SurfaceState
              variant="empty"
              title="Chưa có yêu cầu trả kho"
              description="Gửi yêu cầu trả kho từ trang kho đang thuê của bạn."
              action={{ label: "Kho của tôi", onClick: () => navigate("/my-storage") }}
            />
          ) : (
            returns.data.map((request) => (
              <button
                key={request.id}
                type="button"
                className="flex flex-wrap items-center justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
                onClick={() => void openReturn(request.id)}
              >
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <Undo2 size={15} className="text-muted" />
                    <strong className="text-small font-semibold text-ink">
                      Trả khoang {request.unitCode ?? "—"}
                    </strong>
                    <StatusTag tone={RETURN_TONE[request.status] ?? "neutral"}>
                      {RETURN_STATUS_LABEL[request.status] ?? request.status}
                    </StatusTag>
                  </div>
                  <span className="mt-1 block text-label text-muted">
                    Ngày mong muốn {formatDate(request.preferredDate)}
                    {request.appointmentStart
                      ? ` · hẹn ${formatDateTime(request.appointmentStart)}`
                      : ""}
                  </span>
                </div>
                <ChevronRight size={16} className="text-muted" />
              </button>
            ))
          ))}
      </div>

      {openReturnId !== null && (
        <DetailPanel
          title="Biên bản trả kho"
          eyebrow={`Yêu cầu #${openReturnId}`}
          onClose={() => setOpenReturnId(null)}
        >
          {checkoutError ? (
            <SurfaceState
              variant="error"
              title="Không tải được biên bản"
              description={checkoutError}
            />
          ) : !checkout ? (
            <SurfaceState variant="loading" title="Đang tải biên bản…" />
          ) : (
            <div className="grid gap-4 text-small">
              <div className="grid gap-1">
                <span className="text-label text-muted">Kết quả</span>
                <strong className="text-ink">
                  {CHECKOUT_RESULT_LABEL[checkout.result ?? ""] ??
                    checkout.result ??
                    "Chưa kiểm tra"}
                </strong>
              </div>
              <div className="grid gap-1">
                <span className="text-label text-muted">Khoang đã dọn trống</span>
                <strong className="text-ink">{checkout.unitEmptied ? "Có" : "Chưa"}</strong>
              </div>
              <div className="grid gap-1">
                <span className="text-label text-muted">Tiền cọc</span>
                <strong className="text-ink">{formatMoney(checkout.depositAmount ?? 0)}</strong>
              </div>
              <div className="grid gap-1">
                <span className="text-label text-muted">Tổng phí trả kho</span>
                <strong className="text-ink">
                  {checkout.totalCharges === null ? "—" : formatMoney(checkout.totalCharges)}
                </strong>
              </div>
              <div className="grid gap-1">
                <span className="text-label text-muted">Hoàn lại</span>
                <strong className="text-ink">
                  {checkout.refundAmount === null ? "—" : formatMoney(checkout.refundAmount)}
                </strong>
              </div>
              {checkout.damages && (
                <div className="grid gap-1">
                  <span className="text-label text-muted">Hư hỏng ghi nhận</span>
                  <span className="text-ink">{checkout.damages}</span>
                </div>
              )}
              {checkout.note && (
                <div className="grid gap-1">
                  <span className="text-label text-muted">Ghi chú</span>
                  <span className="text-ink">{checkout.note}</span>
                </div>
              )}
              {checkout.completedAt && (
                <span className="text-label text-muted">
                  Hoàn tất {formatDateTime(checkout.completedAt)}
                </span>
              )}
            </div>
          )}
        </DetailPanel>
      )}
    </div>
  );
}
