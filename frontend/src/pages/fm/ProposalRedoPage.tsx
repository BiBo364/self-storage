import { ArrowLeft, Check, History } from "lucide-react";
import { useState } from "react";
import { formatDate, formatDateTime, formatMoney } from "../../app/format";
import { pathId } from "../../app/routes";
import {
  fetchRentalOrder,
  labelOf,
  loadErrorState,
  ORDER_STATUS_LABEL,
  ORDER_STATUS_TONE,
  PROPOSAL_STATUS_LABEL,
  PROPOSAL_STATUS_TONE,
  reproposeUnit,
  toneOf,
  useFmAction,
  useFmLoad,
} from "../../app/services/fm";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

export function ProposalRedoPage({ navigate }: { navigate: Navigate }) {
  // /fm/rental-orders/{orderId}/re-propose
  const orderId = pathId(2);
  const [selectedUnitId, setSelectedUnitId] = useState<number | null>(null);
  const [overrideReason, setOverrideReason] = useState("");
  const [sentUnitCode, setSentUnitCode] = useState<string | null>(null);
  const action = useFmAction();

  const detail = useFmLoad(`repropose:${orderId}`, () =>
    orderId === null ? Promise.resolve(null) : fetchRentalOrder(orderId),
  );

  const backToOrders = {
    label: "Về danh sách đơn thuê",
    onClick: () => navigate("/fm/rental-orders"),
  };

  if (orderId === null) {
    return (
      <SurfaceState variant="not-found" title="Không tìm thấy đơn thuê" action={backToOrders} />
    );
  }
  if (detail.error) {
    return (
      <SurfaceState
        {...loadErrorState(detail.error, "đơn thuê")}
        action={
          detail.error.status === 403 || detail.error.status === 404
            ? backToOrders
            : { label: "Thử lại", onClick: detail.reload }
        }
      />
    );
  }
  if (!detail.data) {
    return <SurfaceState variant="loading" title="Đang tải đơn thuê…" />;
  }

  const order = detail.data;
  const selectedUnit = order.availableUnits.find((unit) => unit.id === selectedUnitId) ?? null;
  const needsOverride = selectedUnit?.previouslyRejected ?? false;

  async function submit() {
    if (!selectedUnit) return;
    const outcome = await action.run("submit", () =>
      reproposeUnit(order.id, selectedUnit.id, needsOverride ? overrideReason : undefined),
    );
    if (outcome.ok) setSentUnitCode(selectedUnit.code);
    else detail.reload();
  }

  if (sentUnitCode) {
    return (
      <div className="grid gap-6">
        <div className="bg-surface p-6">
          <h1 className="m-0 text-title text-ink">Đã gửi đề xuất mới</h1>
          <p className="mt-2 text-small text-muted">
            Khách {order.customer.name ?? order.customer.email} nhận thông báo đề xuất khoang{" "}
            {sentUnitCode} cho đơn #{order.id}.
          </p>
          <div className="mt-5">
            <Button onClick={() => navigate("/fm/rental-orders")}>Về danh sách đơn thuê</Button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="grid gap-6">
      <div>
        <button
          className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-small text-muted hover:text-ink"
          onClick={() => navigate("/fm/rental-orders")}
        >
          <ArrowLeft size={14} /> Đơn thuê kho
        </button>
        <h1 className="mt-2 text-title text-ink">Đề xuất lại khoang · Đơn #{order.id}</h1>
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <StatusTag tone={toneOf(ORDER_STATUS_TONE, order.status)}>
            {labelOf(ORDER_STATUS_LABEL, order.status)}
          </StatusTag>
          <span className="text-label text-muted">
            Khách đã từ chối {order.rejectionCount}/{order.maxRejectionCount} lần · hạn đơn{" "}
            {formatDateTime(order.expiresAt)}
          </span>
        </div>
      </div>

      <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)] gap-6 max-[900px]:grid-cols-1">
        <div className="grid content-start gap-6">
          <section className="bg-surface p-5">
            <SectionTitle>Khách hàng &amp; nhu cầu</SectionTitle>
            <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3 text-small">
              <Field label="Khách hàng" value={order.customer.name} />
              <Field label="Email" value={order.customer.email} />
              <Field label="Số điện thoại" value={order.customer.phone} />
              <Field label="Loại khoang" value={order.requested.unitTypeName} />
              <Field label="Bắt đầu thuê" value={formatDate(order.requested.startDate)} />
              <Field label="Thời hạn" value={`${order.requested.period} tháng`} />
            </dl>
          </section>

          <section className="bg-surface p-5">
            <SectionTitle>
              <History size={15} /> Lịch sử đề xuất
            </SectionTitle>
            {order.proposals.length === 0 ? (
              <p className="mt-3 text-label text-muted">Chưa có đề xuất nào.</p>
            ) : (
              <div className="mt-3 grid gap-1.5">
                {order.proposals.map((proposal) => (
                  <div key={proposal.id} className="bg-surface-subtle p-3">
                    <div className="flex items-center justify-between gap-3">
                      <strong className="text-small text-ink">{proposal.unitCode}</strong>
                      <StatusTag tone={toneOf(PROPOSAL_STATUS_TONE, proposal.status)}>
                        {labelOf(PROPOSAL_STATUS_LABEL, proposal.status)}
                      </StatusTag>
                    </div>
                    <span className="mt-1 block text-label text-muted">
                      {formatDateTime(proposal.createdAt)}
                    </span>
                    {proposal.note && (
                      <p className="mt-1 text-label text-ink">Lý do: {proposal.note}</p>
                    )}
                  </div>
                ))}
              </div>
            )}
          </section>
        </div>

        <section className="grid content-start gap-4 bg-surface p-5">
          <SectionTitle>Chọn khoang trống cùng loại</SectionTitle>
          {order.availableUnits.length === 0 ? (
            <SurfaceState
              variant="empty"
              title="Không còn khoang trống cùng loại"
              description="Có thể huỷ đơn từ danh sách đơn thuê nếu không thể đề xuất khoang khác."
              action={backToOrders}
            />
          ) : (
            <div className="grid gap-1.5" role="radiogroup" aria-label="Khoang trống">
              {order.availableUnits.map((unit) => {
                const active = unit.id === selectedUnitId;
                return (
                  <button
                    key={unit.id}
                    role="radio"
                    aria-checked={active}
                    className={`flex items-center justify-between gap-3 border-2 p-3 text-left transition-colors ${
                      active
                        ? "border-brand bg-surface-subtle"
                        : "border-transparent bg-surface-subtle hover:border-control-border"
                    }`}
                    onClick={() => {
                      setSelectedUnitId(unit.id);
                      action.clear();
                    }}
                  >
                    <span>
                      <strong className="block text-small text-ink">{unit.code}</strong>
                      <span className="text-label text-muted">
                        {unit.size ?? order.requested.unitTypeName} ·{" "}
                        {formatMoney(unit.monthlyPrice)}/tháng
                      </span>
                    </span>
                    {unit.previouslyRejected && (
                      <StatusTag tone="danger">Khách đã từ chối</StatusTag>
                    )}
                  </button>
                );
              })}
            </div>
          )}

          {needsOverride && (
            <label className="grid gap-1.5 text-label text-muted" htmlFor="override-reason">
              Lý do đề xuất lại khoang khách đã từ chối (bắt buộc)
              <div className={INPUT_WRAP}>
                <textarea
                  id="override-reason"
                  className={`${INPUT_ELEMENT} min-h-[72px] py-2`}
                  value={overrideReason}
                  onChange={(event) => setOverrideReason(event.target.value)}
                  placeholder="VD: Khoang đã được sửa cửa theo phản hồi của khách"
                />
              </div>
            </label>
          )}

          {action.error && <DemoNotice tone="error">{action.error}</DemoNotice>}

          {order.availableUnits.length > 0 && (
            <div>
              <Button
                pending={action.pending === "submit"}
                disabled={!selectedUnit || (needsOverride && !overrideReason.trim())}
                onClick={submit}
              >
                {action.pending !== "submit" && <Check size={16} />}
                Gửi đề xuất cho khách
              </Button>
            </div>
          )}
        </section>
      </div>
    </div>
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
