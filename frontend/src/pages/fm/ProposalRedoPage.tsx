import { AlertTriangle, ArrowLeft, Check, History } from "lucide-react";
import { useState } from "react";
import type { Navigate } from "../../app/types";
import { formatPrice, unitTypes } from "../../mocks/catalog";
import {
  fmUnits,
  MAX_ORDER_REJECTIONS,
  rentalOrders,
  type ProposalFeedbackEntry,
} from "../../mocks/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const SOURCE_LABEL: Record<ProposalFeedbackEntry["source"], string> = {
  customer: "Khách từ chối",
  checkin: "Từ chối tại chỗ lúc check-in",
};

function getOrderId() {
  const segments = window.location.pathname.split("/").filter(Boolean);
  return segments[segments.length - 2] ?? "";
}

function unitTypeName(unitTypeId: string) {
  return unitTypes.find((type) => type.id === unitTypeId)?.name ?? unitTypeId;
}

export function ProposalRedoPage({ navigate }: { navigate: Navigate }) {
  const orderId = getOrderId();
  const order = rentalOrders.find((item) => item.id === orderId);

  const [selectedUnitId, setSelectedUnitId] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [sent, setSent] = useState(false);

  if (!order) {
    return (
      <SurfaceState
        variant="not-found"
        title="Không tìm thấy đơn hàng"
        action={{ label: "Về hàng chờ yêu cầu", onClick: () => navigate("/fm/rental-requests") }}
      />
    );
  }

  const availableUnits = fmUnits.filter(
    (unit) => unit.unitTypeId === order.unitTypeId && unit.status === "Available",
  );
  const selectedUnit = fmUnits.find((unit) => unit.id === selectedUnitId);
  const newDepositEstimate = selectedUnit
    ? (unitTypes.find((type) => type.id === selectedUnit.unitTypeId)?.monthlyPrice ?? 0)
    : 0;
  const depositDiff = order.afterDeposit ? newDepositEstimate - (order.previousDeposit ?? 0) : 0;

  function submit() {
    if (!selectedUnitId) return;
    setIsSubmitting(true);
    window.setTimeout(() => {
      setIsSubmitting(false);
      setSent(true);
    }, 700);
  }

  return (
    <div className="grid gap-6">
      <button
        className="inline-flex w-fit items-center gap-1.5 border-0 bg-transparent p-0 text-small font-semibold text-accent hover:text-ink"
        onClick={() => navigate("/fm/rental-requests")}
      >
        <ArrowLeft size={16} /> Về hàng chờ yêu cầu
      </button>

      <div>
        <span className="text-label text-muted">{order.customerName}</span>
        <h1 className="mt-1 text-title text-ink">Đề xuất lại khoang</h1>
        <p className="mt-1.5 text-small text-muted">
          Cần: {unitTypeName(order.unitTypeId)} · {order.desiredSize}
        </p>
      </div>

      <div className="grid grid-cols-[minmax(0,1fr)_360px] items-start gap-6 max-[980px]:grid-cols-1">
        <div className="grid gap-3">
          <section className="bg-surface p-5">
            <SectionTitle>
              <span className="inline-flex items-center gap-2">
                <History size={16} /> Lịch sử đề xuất trước
              </span>
            </SectionTitle>
            <div className="mt-4 grid gap-2">
              {order.rejectionHistory.map((entry) => (
                <div
                  key={`${entry.unitCode}-${entry.rejectedAt}`}
                  className="bg-surface-subtle p-3.5"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <strong className="text-small font-semibold text-ink">
                      Khoang {entry.unitCode}
                    </strong>
                    <StatusTag tone={entry.source === "customer" ? "danger" : "warning"}>
                      {SOURCE_LABEL[entry.source]}
                    </StatusTag>
                  </div>
                  <p className="mt-1.5 text-small text-muted">{entry.note}</p>
                  <p className="mt-1 text-label text-muted">{entry.rejectedAt}</p>
                </div>
              ))}
            </div>
            <p className="mt-4 text-label text-muted">
              Đã từ chối {order.rejectionHistory.length}/{MAX_ORDER_REJECTIONS} lần cho phép.
            </p>
          </section>

          {order.status === "Canceled" ? (
            <SurfaceState
              variant="forbidden"
              title="Đơn đã bị huỷ"
              description="Đã đạt ngưỡng tối đa số lần từ chối — hệ thống tự huỷ đơn. Chỉ có thể xem lại lịch sử, không thể đề xuất tiếp."
            />
          ) : sent ? (
            <DemoNotice tone="success">
              Đã gửi đề xuất khoang {selectedUnit?.code} cho khách. Khách sẽ nhận thông báo để xác
              nhận.
            </DemoNotice>
          ) : (
            <section className="bg-surface p-5">
              <SectionTitle>Chọn khoang mới</SectionTitle>
              {order.afterDeposit && (
                <div className="mt-4">
                  <DemoNotice tone="pending">
                    Khách đã đặt cọc trước đó ({formatPrice(order.previousDeposit ?? 0)}đ) — cần đổi
                    khoang do phát sinh sự cố. Kiểm tra chênh lệch cọc bên dưới trước khi gửi.
                  </DemoNotice>
                </div>
              )}
              <label className="mt-5 grid gap-1.5 text-label text-muted">
                Khoang trống phù hợp
                <select
                  className="rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink"
                  value={selectedUnitId}
                  onChange={(event) => setSelectedUnitId(event.target.value)}
                >
                  <option value="">— Chọn khoang —</option>
                  {availableUnits.map((unit) => (
                    <option key={unit.id} value={unit.id}>
                      {unit.code} · {unit.size}
                    </option>
                  ))}
                </select>
              </label>
              {availableUnits.length === 0 && (
                <p className="mt-2 flex items-center gap-2 text-small text-danger">
                  <AlertTriangle size={14} /> Hiện không còn khoang trống đúng loại yêu cầu.
                </p>
              )}
              {order.afterDeposit && selectedUnit && (
                <div className="mt-4 grid gap-2 bg-surface-subtle p-3.5 text-small">
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-muted">Cọc đã thu (khoang cũ)</span>
                    <strong className="font-semibold text-ink">
                      {formatPrice(order.previousDeposit ?? 0)} đ
                    </strong>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-muted">Cọc ước tính (khoang mới)</span>
                    <strong className="font-semibold text-ink">
                      {formatPrice(newDepositEstimate)} đ
                    </strong>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-muted">Chênh lệch</span>
                    <strong
                      className={`font-semibold ${depositDiff >= 0 ? "text-danger" : "text-success"}`}
                    >
                      {depositDiff >= 0 ? "+" : ""}
                      {formatPrice(depositDiff)} đ
                    </strong>
                  </div>
                </div>
              )}
              <Button
                className="mt-5"
                pending={isSubmitting}
                disabled={!selectedUnitId}
                onClick={submit}
              >
                {!isSubmitting && <Check size={16} />}
                Đề xuất lại
              </Button>
            </section>
          )}
        </div>

        <aside className="grid gap-3 bg-surface p-5">
          <SectionTitle>Tóm tắt yêu cầu</SectionTitle>
          <div>
            <span className="text-label text-muted">Loại kho</span>
            <strong className="mt-0.5 block text-small font-semibold text-ink">
              {unitTypeName(order.unitTypeId)}
            </strong>
          </div>
          <div>
            <span className="text-label text-muted">Diện tích mong muốn</span>
            <strong className="mt-0.5 block text-small font-semibold text-ink">
              {order.desiredSize}
            </strong>
          </div>
        </aside>
      </div>
    </div>
  );
}
