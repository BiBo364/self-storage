import { AlertTriangle, ArrowLeft, Check, History } from "lucide-react";
import { useEffect, useState } from "react";
import { ApiError } from "../../app/api";
import { formatDate, formatMoney } from "../../app/format";
import { fetchRentalOrder, reproposeUnit } from "../../app/proposals";
import type { FmRentalOrder, Navigate, ProposalSnapshot, ProposalStatus } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

type LoadState =
  | { kind: "loading" }
  | { kind: "ready"; order: FmRentalOrder }
  | { kind: "not-found" }
  | { kind: "forbidden"; message: string }
  | { kind: "error"; message: string };

const STATUS_LABEL: Record<ProposalStatus, string> = {
  Pending: "Chờ khách phản hồi",
  Agreed: "Khách đã đồng ý",
  Rejected: "Khách từ chối",
  Canceled: "Đã huỷ",
};

const STATUS_TONE: Record<ProposalStatus, "warning" | "success" | "danger" | "neutral"> = {
  Pending: "warning",
  Agreed: "success",
  Rejected: "danger",
  Canceled: "neutral",
};

function getOrderId(): number {
  const segments = window.location.pathname.split("/").filter(Boolean);
  return Number(segments[segments.length - 2]);
}

async function loadOrder(orderId: number): Promise<LoadState> {
  if (!Number.isInteger(orderId) || orderId <= 0) return { kind: "not-found" };
  try {
    return { kind: "ready", order: await fetchRentalOrder(orderId) };
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return { kind: "not-found" };
    if (error instanceof ApiError && error.status === 403) {
      return { kind: "forbidden", message: error.message };
    }
    return {
      kind: "error",
      message: error instanceof Error ? error.message : "Không tải được đơn thuê.",
    };
  }
}

export function ProposalRedoPage({ navigate }: { navigate: Navigate }) {
  const orderId = getOrderId();
  const [state, setState] = useState<LoadState>({ kind: "loading" });
  const [selectedUnitId, setSelectedUnitId] = useState("");
  const [overrideReason, setOverrideReason] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState("");
  const [sent, setSent] = useState<{ proposal: ProposalSnapshot; unitCode: string } | null>(null);

  useEffect(() => {
    let active = true;
    async function loadInitial() {
      const next = await loadOrder(orderId);
      if (active) setState(next);
    }
    void loadInitial();
    return () => {
      active = false;
    };
  }, [orderId]);

  async function reload() {
    setState({ kind: "loading" });
    setState(await loadOrder(orderId));
  }

  const backToQueue = () => navigate("/fm/rental-requests");

  if (state.kind === "loading") {
    return <SurfaceState variant="loading" title="Đang tải đơn thuê…" />;
  }
  if (state.kind === "not-found") {
    return (
      <SurfaceState
        variant="not-found"
        title="Không tìm thấy đơn hàng"
        action={{ label: "Về hàng chờ yêu cầu", onClick: backToQueue }}
      />
    );
  }
  if (state.kind === "forbidden") {
    return (
      <SurfaceState
        variant="forbidden"
        title="Không có quyền xem đơn này"
        description={state.message}
        action={{ label: "Về hàng chờ yêu cầu", onClick: backToQueue }}
      />
    );
  }
  if (state.kind === "error") {
    return (
      <SurfaceState
        variant="error"
        title="Không tải được đơn thuê"
        description={state.message}
        action={{ label: "Thử lại", onClick: () => void reload() }}
      />
    );
  }

  const { order } = state;
  const selectedUnit = order.availableUnits.find((unit) => String(unit.id) === selectedUnitId);
  const needsOverride = selectedUnit?.previouslyRejected ?? false;
  const hasPending = order.proposals.some((proposal) => proposal.status === "Pending");
  const isClosed = order.status === "Canceled" || order.status === "Done";
  const lastProposal = order.proposals[0] ?? null;
  const canRepropose =
    !isClosed &&
    !hasPending &&
    (order.status === "Pending" || order.needsReproposal) &&
    lastProposal?.status !== "Agreed";

  async function submit() {
    if (!selectedUnit) return;
    setIsSubmitting(true);
    setSubmitError("");
    try {
      const proposal = await reproposeUnit(
        order.id,
        selectedUnit.id,
        needsOverride ? overrideReason.trim() : undefined,
      );
      setSent({ proposal, unitCode: selectedUnit.code });
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : "Không gửi được đề xuất.");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <div className="grid gap-6">
      <button
        className="inline-flex w-fit items-center gap-1.5 border-0 bg-transparent p-0 text-small font-semibold text-accent hover:text-ink"
        onClick={backToQueue}
      >
        <ArrowLeft size={16} /> Về hàng chờ yêu cầu
      </button>

      <div>
        <span className="text-label text-muted">
          {order.customer.name} · Đơn {order.id}
        </span>
        <h1 className="mt-1 text-title text-ink">Đề xuất lại khoang</h1>
        <p className="mt-1.5 text-small text-muted">
          Cần: {order.requested.unitTypeName} · {order.requested.period} tháng từ{" "}
          {formatDate(order.requested.startDate)}
        </p>
      </div>

      <div className="grid grid-cols-[minmax(0,1fr)_360px] items-start gap-6 max-[980px]:grid-cols-1">
        <div className="grid gap-3">
          <section className="bg-surface p-5">
            <SectionTitle>Trạng thái đơn &amp; yêu cầu</SectionTitle>
            <div className="mt-4 grid gap-2 text-small">
              <div className="flex items-center justify-between gap-4">
                <span className="text-muted">Yêu cầu</span>
                <strong className="font-semibold text-ink">{order.requestStatus}</strong>
              </div>
              <div className="flex items-center justify-between gap-4">
                <span className="text-muted">Đơn thuê</span>
                <strong className="font-semibold text-ink">{order.status}</strong>
              </div>
              <div className="flex items-center justify-between gap-4">
                <span className="text-muted">Cần đề xuất lại</span>
                <strong className="font-semibold text-ink">{canRepropose ? "Có" : "Không"}</strong>
              </div>
              <div className="flex items-center justify-between gap-4">
                <span className="text-muted">Đã từ chối</span>
                <strong className="font-semibold text-ink">
                  {order.rejectionCount}/{order.maxRejectionCount}
                </strong>
              </div>
            </div>
            {order.proposals[0] && (
              <div className="mt-4 bg-surface-subtle p-3.5">
                <p className="text-label text-muted">Đề xuất gần nhất</p>
                <p className="mt-1 text-small text-ink">
                  Khoang {order.proposals[0].unitCode} · {STATUS_LABEL[order.proposals[0].status]}
                </p>
                {order.proposals[0].note && (
                  <p className="mt-1 text-small text-muted">{order.proposals[0].note}</p>
                )}
                <p className="mt-1 text-label text-muted">
                  {formatDate(order.proposals[0].createdAt)}
                </p>
              </div>
            )}
          </section>

          <section className="bg-surface p-5">
            <SectionTitle>
              <span className="inline-flex items-center gap-2">
                <History size={16} /> Lịch sử đề xuất trước
              </span>
            </SectionTitle>
            {order.proposals.length === 0 ? (
              <p className="mt-4 text-small text-muted">Đơn này chưa có đề xuất nào.</p>
            ) : (
              <div className="mt-4 grid gap-2">
                {order.proposals.map((entry) => (
                  <div key={entry.id} className="bg-surface-subtle p-3.5">
                    <div className="flex flex-wrap items-center gap-2">
                      <strong className="text-small font-semibold text-ink">
                        Khoang {entry.unitCode}
                      </strong>
                      <StatusTag tone={STATUS_TONE[entry.status]}>
                        {STATUS_LABEL[entry.status]}
                      </StatusTag>
                    </div>
                    {entry.note && <p className="mt-1.5 text-small text-muted">{entry.note}</p>}
                    <p className="mt-1 text-label text-muted">{formatDate(entry.createdAt)}</p>
                  </div>
                ))}
              </div>
            )}
            <p className="mt-4 text-label text-muted">
              Đã từ chối {order.rejectionCount}/{order.maxRejectionCount} lần cho phép.
            </p>
          </section>

          {isClosed ? (
            <SurfaceState
              variant="forbidden"
              title="Đơn đã đóng"
              description={
                order.cancelReason
                  ? `Đơn đã bị huỷ: ${order.cancelReason}. Chỉ có thể xem lại lịch sử.`
                  : "Đơn đã hoàn tất, không thể đề xuất tiếp."
              }
            />
          ) : sent ? (
            <DemoNotice tone="success">
              Đã gửi đề xuất khoang {sent.unitCode} cho khách. Khách sẽ nhận thông báo để xác nhận.
            </DemoNotice>
          ) : hasPending ? (
            <DemoNotice tone="pending">
              Đơn đang có đề xuất chờ khách phản hồi. Chờ khách đồng ý hoặc từ chối trước khi đề
              xuất khoang khác.
            </DemoNotice>
          ) : !canRepropose ? (
            <DemoNotice tone="info">
              Đơn đang chờ bước tiếp theo (thanh toán/tiến trình), chưa cần đề xuất lại.
            </DemoNotice>
          ) : (
            <section className="bg-surface p-5">
              <SectionTitle>Chọn khoang mới</SectionTitle>
              <label className="mt-5 grid gap-1.5 text-label text-muted">
                Khoang trống phù hợp
                <select
                  className="rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink"
                  value={selectedUnitId}
                  onChange={(event) => {
                    setSelectedUnitId(event.target.value);
                    setSubmitError("");
                  }}
                >
                  <option value="">— Chọn khoang —</option>
                  {order.availableUnits.map((unit) => (
                    <option key={unit.id} value={String(unit.id)}>
                      {unit.code}
                      {unit.size ? ` · ${unit.size}` : ""} · {formatMoney(unit.monthlyPrice)}
                      {unit.previouslyRejected ? " (khách đã từ chối)" : ""}
                    </option>
                  ))}
                </select>
              </label>
              {order.availableUnits.length === 0 && (
                <p className="mt-2 flex items-center gap-2 text-small text-danger">
                  <AlertTriangle size={14} /> Hiện không còn khoang trống đúng loại yêu cầu.
                </p>
              )}
              {needsOverride && (
                <label className="mt-4 grid gap-1.5 text-label text-muted">
                  Lý do đề xuất lại khoang khách đã từ chối
                  <textarea
                    className="min-h-[70px] rounded-[2px] border border-rule-strong bg-surface p-2.5 text-small text-ink"
                    value={overrideReason}
                    onChange={(event) => setOverrideReason(event.target.value)}
                    placeholder="Lý do sẽ được ghi vào nhật ký thao tác…"
                  />
                </label>
              )}
              {submitError && (
                <div className="mt-4">
                  <DemoNotice tone="error">{submitError}</DemoNotice>
                </div>
              )}
              <Button
                className="mt-5"
                pending={isSubmitting}
                disabled={!selectedUnit || (needsOverride && !overrideReason.trim())}
                onClick={() => void submit()}
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
            <span className="text-label text-muted">Khách hàng</span>
            <strong className="mt-0.5 block text-small font-semibold text-ink">
              {order.customer.name}
            </strong>
            <span className="mt-0.5 block text-label text-muted">
              {order.customer.email} · {order.customer.phone}
            </span>
          </div>
          <div>
            <span className="text-label text-muted">Loại kho</span>
            <strong className="mt-0.5 block text-small font-semibold text-ink">
              {order.requested.unitTypeName}
            </strong>
          </div>
          <div>
            <span className="text-label text-muted">Trạng thái đơn</span>
            <strong className="mt-0.5 block text-small font-semibold text-ink">
              {order.status}
            </strong>
          </div>
        </aside>
      </div>
    </div>
  );
}
