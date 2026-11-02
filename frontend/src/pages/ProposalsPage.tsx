import { AlertTriangle, ArrowRight, Check, MapPin, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../app/api";
import { formatDate, formatMoney } from "../app/format";
import { agreeProposal, fetchMyProposals, rejectProposal } from "../app/proposals";
import { listUnitTypes } from "../app/unitTypes";
import type { CustomerProposal, DepositInvoice, Navigate, UnitTypeSummary } from "../app/types";
import { rentedStorage } from "../mocks/customer";
import { Button } from "../components/ui/Button";
import { DemoNotice } from "../components/ui/DemoNotice";
import { DetailPanel } from "../components/ui/DetailPanel";
import { SurfaceState } from "../components/ui/SurfaceState";

type Tab = "active" | "pending";
type LoadState = "loading" | "ready" | "error";

type CardOutcome =
  | { kind: "agreed"; invoice: DepositInvoice | null }
  | { kind: "unavailable"; message: string }
  | { kind: "rejected" };

type LoadResult = { ok: true; data: CustomerProposal[] } | { ok: false; message: string };

async function loadPendingProposals(): Promise<LoadResult> {
  try {
    return { ok: true, data: await fetchMyProposals("Pending") };
  } catch (error) {
    return {
      ok: false,
      message: error instanceof Error ? error.message : "Không tải được danh sách đề xuất.",
    };
  }
}

export function ProposalsPage({ navigate }: { navigate: Navigate }) {
  const [tab, setTab] = useState<Tab>("pending");
  const [items, setItems] = useState<CustomerProposal[]>([]);
  const [loadState, setLoadState] = useState<LoadState>("loading");
  const [loadError, setLoadError] = useState("");
  const [outcomes, setOutcomes] = useState<Record<number, CardOutcome>>({});
  const [actionError, setActionError] = useState<{ id: number; message: string } | null>(null);
  const [orderCanceled, setOrderCanceled] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rejectingId, setRejectingId] = useState<number | null>(null);
  const [rejectNote, setRejectNote] = useState("");
  const [detailProposal, setDetailProposal] = useState<CustomerProposal | null>(null);
  const [detailUnitType, setDetailUnitType] = useState<UnitTypeSummary | null>(null);
  const [detailState, setDetailState] = useState<"idle" | "loading" | "ready" | "error">("idle");
  const [detailError, setDetailError] = useState("");
  const detailRequest = useRef(0);

  const applyLoad = useCallback((result: LoadResult) => {
    if (result.ok) {
      setItems(result.data);
      setLoadState("ready");
    } else {
      setLoadError(result.message);
      setLoadState("error");
    }
  }, []);

  useEffect(() => {
    let active = true;
    async function loadInitial() {
      const result = await loadPendingProposals();
      if (active) applyLoad(result);
    }
    void loadInitial();
    return () => {
      active = false;
    };
  }, [applyLoad]);

  async function reload() {
    setLoadState("loading");
    applyLoad(await loadPendingProposals());
  }

  async function openUnitDetail(proposal: CustomerProposal) {
    const requestId = ++detailRequest.current;
    setDetailProposal(proposal);
    setDetailUnitType(null);
    setDetailState("loading");
    setDetailError("");
    try {
      const types = await listUnitTypes(proposal.facilityId);
      if (detailRequest.current !== requestId) return;
      setDetailUnitType(types.find((type) => type.name === proposal.unitTypeName) ?? null);
      setDetailState("ready");
    } catch (error) {
      if (detailRequest.current !== requestId) return;
      setDetailError(error instanceof Error ? error.message : "Không tải được thông tin khoang.");
      setDetailState("error");
    }
  }

  function closeUnitDetail() {
    detailRequest.current += 1;
    setDetailProposal(null);
    setDetailUnitType(null);
    setDetailState("idle");
    setDetailError("");
  }

  const awaitingCount = items.filter((proposal) => !outcomes[proposal.id]).length;

  async function agree(proposal: CustomerProposal) {
    setBusyId(proposal.id);
    setActionError(null);
    try {
      const result = await agreeProposal(proposal.id);
      setOutcomes((current) => ({
        ...current,
        [proposal.id]: { kind: "agreed", invoice: result.invoice },
      }));
    } catch (error) {
      const message = error instanceof Error ? error.message : "Không thể đồng ý đề xuất.";
      if (error instanceof ApiError && error.status === 409) {
        setOutcomes((current) => ({ ...current, [proposal.id]: { kind: "unavailable", message } }));
      } else {
        setActionError({ id: proposal.id, message });
      }
    } finally {
      setBusyId(null);
    }
  }

  async function confirmReject(proposal: CustomerProposal) {
    setBusyId(proposal.id);
    setActionError(null);
    try {
      const result = await rejectProposal(proposal.id, rejectNote.trim());
      setOutcomes((current) => ({ ...current, [proposal.id]: { kind: "rejected" } }));
      if (result.orderCanceled) setOrderCanceled(true);
      setRejectingId(null);
      setRejectNote("");
    } catch (error) {
      const message = error instanceof Error ? error.message : "Không thể từ chối đề xuất.";
      setActionError({ id: proposal.id, message });
    } finally {
      setBusyId(null);
    }
  }

  function tabClass(active: boolean) {
    return `inline-flex items-center gap-2 border-0 px-2.5 py-1.5 text-small font-semibold ${
      active
        ? "bg-brand text-signal-ink"
        : "bg-transparent text-muted hover:bg-surface-subtle hover:text-ink"
    }`;
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Kiểm tra kho của tôi</h1>
        <p className="mt-1.5 text-small text-muted">
          Xem lại đề xuất khoang từ đội ngũ vận hành và xác nhận trước khi đặt cọc.
        </p>
      </div>

      <div className="flex flex-wrap gap-1.5">
        <button className={tabClass(tab === "active")} onClick={() => setTab("active")}>
          Đang sử dụng
          <span
            className={`px-1.5 text-label ${
              tab === "active" ? "bg-signal-ink text-brand" : "bg-surface text-muted"
            }`}
          >
            {rentedStorage.length}
          </span>
        </button>
        <button className={tabClass(tab === "pending")} onClick={() => setTab("pending")}>
          Chờ duyệt
          <span
            className={`px-1.5 text-label ${
              tab === "pending" ? "bg-signal-ink text-brand" : "bg-surface text-muted"
            }`}
          >
            {awaitingCount}
          </span>
        </button>
      </div>

      {tab === "active" && (
        <>
          <DemoNotice tone="info">
            Danh sách kho đang sử dụng chưa có API — tab này đang dùng dữ liệu minh hoạ.
          </DemoNotice>
          {rentedStorage.length === 0 ? (
            <SurfaceState variant="empty" title="Chưa có kho đang sử dụng" />
          ) : (
            <div className="grid gap-2.5">
              {rentedStorage.map((storage) => (
                <div
                  key={storage.id}
                  className="flex flex-wrap items-center justify-between gap-4 bg-surface p-4"
                >
                  <div>
                    <span className="text-label text-muted">
                      Khoang {storage.unitCode} · {storage.size}
                    </span>
                    <h2 className="mt-1 text-[16px] font-semibold text-ink">{storage.facility}</h2>
                    <p className="mt-1 flex items-center gap-1.5 text-small text-muted">
                      <MapPin size={14} /> {storage.district}
                    </p>
                  </div>
                  <Button variant="secondary" size="sm" onClick={() => navigate("/my-storage")}>
                    Xem chi tiết <ArrowRight size={15} />
                  </Button>
                </div>
              ))}
            </div>
          )}
        </>
      )}

      {tab === "pending" && loadState === "loading" && (
        <SurfaceState variant="loading" title="Đang tải đề xuất khoang…" />
      )}

      {tab === "pending" && loadState === "error" && (
        <SurfaceState
          variant="error"
          title="Không tải được đề xuất khoang"
          description={loadError}
          action={{ label: "Thử lại", onClick: () => void reload() }}
        />
      )}

      {tab === "pending" &&
        loadState === "ready" &&
        (items.length === 0 ? (
          <SurfaceState
            variant="empty"
            title="Chưa có đề xuất nào đang chờ"
            description="Khi đội ngũ vận hành đề xuất khoang cho yêu cầu của bạn, đề xuất sẽ hiện ở đây."
          />
        ) : (
          <div className="grid gap-2.5">
            {items.map((proposal) => {
              const outcome = outcomes[proposal.id];
              const isBusy = busyId === proposal.id;
              const remaining = proposal.maxRejectionCount - proposal.rejectionCount;
              const nearLimit = remaining <= 1;
              return (
                <article key={proposal.id} className="bg-surface p-5">
                  <div className="flex items-start justify-between gap-5 max-[760px]:flex-col">
                    <div>
                      <span className="text-label text-muted">
                        Khoang {proposal.unitCode} · {proposal.unitTypeName}
                      </span>
                      <h2 className="mt-1 text-section text-ink">{proposal.facilityName}</h2>
                      {proposal.facilityAddress && (
                        <p className="mt-1.5 flex items-center gap-1.5 text-small text-muted">
                          <MapPin size={15} /> {proposal.facilityAddress}
                        </p>
                      )}
                    </div>
                    <div className="grid gap-3 text-right max-[760px]:text-left">
                      <div>
                        <span className="text-label text-muted">Giá thuê đề xuất</span>
                        <strong className="mt-0.5 block text-[18px] font-semibold text-ink">
                          {formatMoney(proposal.monthlyPrice)} / tháng
                        </strong>
                      </div>
                      <div>
                        <span className="text-label text-muted">Ngày đề xuất</span>
                        <strong className="mt-0.5 block text-small font-semibold text-ink">
                          {formatDate(proposal.createdAt)}
                        </strong>
                      </div>
                    </div>
                  </div>

                  {outcome?.kind === "unavailable" && (
                    <div className="mt-4">
                      <DemoNotice tone="error">
                        {outcome.message}. Đề xuất đã hết hiệu lực — đội ngũ vận hành sẽ gửi đề xuất
                        khoang khác cho bạn.
                      </DemoNotice>
                    </div>
                  )}

                  {outcome?.kind === "agreed" && (
                    <div className="mt-4">
                      <DemoNotice tone="success">
                        Đã đồng ý khoang này.
                        {outcome.invoice
                          ? ` Hoá đơn đặt cọc ${outcome.invoice.code}: ${formatMoney(outcome.invoice.amount)}${
                              outcome.invoice.dueDate
                                ? `, hạn thanh toán ${formatDate(outcome.invoice.dueDate)}`
                                : ""
                            }.`
                          : " Khoang mới đã được cập nhật cho đơn thuê."}
                      </DemoNotice>
                      <Button
                        variant="secondary"
                        size="sm"
                        className="mt-3"
                        onClick={() => navigate(outcome.invoice ? "/invoices" : "/my-storage")}
                      >
                        {outcome.invoice ? "Xem hoá đơn" : "Về Kho của tôi"}{" "}
                        <ArrowRight size={15} />
                      </Button>
                    </div>
                  )}

                  {outcome?.kind === "rejected" && (
                    <div className="mt-4">
                      <DemoNotice tone="info">
                        Đã từ chối đề xuất này. Đội ngũ vận hành sẽ gửi phương án khác nếu còn
                        khoang phù hợp.
                      </DemoNotice>
                    </div>
                  )}

                  {actionError?.id === proposal.id && (
                    <div className="mt-4">
                      <DemoNotice tone="error">{actionError.message}</DemoNotice>
                    </div>
                  )}

                  {!outcome && nearLimit && (
                    <div className="mt-4">
                      <DemoNotice tone="pending">
                        Bạn đã từ chối {proposal.rejectionCount}/{proposal.maxRejectionCount} lần
                        cho phép. Từ chối thêm {remaining} lần nữa, yêu cầu này sẽ tự huỷ.
                      </DemoNotice>
                    </div>
                  )}

                  {!outcome && (
                    <div className="mt-5 flex flex-wrap items-center gap-2.5">
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => void openUnitDetail(proposal)}
                      >
                        Xem khoang
                      </Button>
                      <Button pending={isBusy} onClick={() => agree(proposal)}>
                        {!isBusy && <Check size={16} />}
                        Đồng ý
                      </Button>
                      <Button
                        variant="secondary"
                        disabled={isBusy}
                        onClick={() => setRejectingId(proposal.id)}
                      >
                        <X size={16} /> Từ chối
                      </Button>
                    </div>
                  )}

                  {rejectingId === proposal.id && !outcome && (
                    <div className="mt-4 bg-surface-subtle p-4">
                      <label className="grid gap-1.5 text-label text-muted">
                        Lý do từ chối
                        <textarea
                          className="min-h-[70px] rounded-[2px] border border-rule-strong bg-surface p-2.5 text-small font-normal text-ink"
                          value={rejectNote}
                          onChange={(event) => setRejectNote(event.target.value)}
                          placeholder="Cho đội ngũ vận hành biết vì sao khoang này chưa phù hợp…"
                        />
                      </label>
                      <div className="mt-3 flex items-center gap-2.5">
                        <Button
                          disabled={!rejectNote.trim() || isBusy}
                          pending={isBusy}
                          onClick={() => confirmReject(proposal)}
                        >
                          Xác nhận từ chối
                        </Button>
                        <button
                          className="border-0 bg-transparent px-3 py-2 text-small text-muted hover:text-ink"
                          onClick={() => {
                            setRejectingId(null);
                            setRejectNote("");
                          }}
                        >
                          Huỷ
                        </button>
                      </div>
                    </div>
                  )}
                </article>
              );
            })}

            {orderCanceled && (
              <div className="bg-danger-soft p-4">
                <p className="flex items-center gap-2 text-small font-semibold text-danger">
                  <AlertTriangle size={16} /> Yêu cầu đã bị huỷ
                </p>
                <p className="mt-1.5 text-label text-muted">
                  Bạn đã từ chối đủ số lần cho phép nên yêu cầu này tự động huỷ. Vui lòng gửi yêu
                  cầu mới nếu vẫn cần thuê kho.
                </p>
                <Button
                  variant="secondary"
                  size="sm"
                  className="mt-3"
                  onClick={() => navigate("/rental-requests/new")}
                >
                  Gửi yêu cầu mới <ArrowRight size={15} />
                </Button>
              </div>
            )}
          </div>
        ))}

      {detailProposal && (
        <DetailPanel
          title={`Khoang ${detailProposal.unitCode}`}
          eyebrow={`${detailProposal.facilityName} · ${detailProposal.unitTypeName}`}
          onClose={closeUnitDetail}
        >
          {detailState === "loading" && (
            <SurfaceState variant="loading" title="Đang tải thông tin khoang…" />
          )}
          {detailState === "error" && <DemoNotice tone="error">{detailError}</DemoNotice>}
          <dl className="mt-5 grid gap-2.5">
            <DetailRow label="Mã khoang" value={detailProposal.unitCode} />
            <DetailRow label="Loại khoang" value={detailProposal.unitTypeName} />
            <DetailRow label="Kích thước khoang" value={detailProposal.unitSize ?? "—"} />
            {detailUnitType && (
              <>
                <DetailRow label="Diện tích" value={`${detailUnitType.area} m²`} />
                <DetailRow
                  label="Kích thước loại"
                  value={`${detailUnitType.width} × ${detailUnitType.depth} × ${detailUnitType.height} m`}
                />
              </>
            )}
            <DetailRow label="Cơ sở" value={detailProposal.facilityName} />
            <DetailRow label="Địa chỉ" value={detailProposal.facilityAddress ?? "—"} />
            <DetailRow
              label="Giá thuê"
              value={`${formatMoney(detailProposal.monthlyPrice)} / tháng`}
            />
            <DetailRow label="Ngày đề xuất" value={formatDate(detailProposal.createdAt)} />
            <DetailRow
              label="Đã từ chối"
              value={`${detailProposal.rejectionCount}/${detailProposal.maxRejectionCount} lần`}
            />
          </dl>
          {detailState === "error" && (
            <Button
              variant="secondary"
              className="mt-4"
              onClick={() => void openUnitDetail(detailProposal)}
            >
              Thử lại
            </Button>
          )}
        </DetailPanel>
      )}
    </div>
  );
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start justify-between gap-4 text-small">
      <dt className="shrink-0 text-muted">{label}</dt>
      <dd className="m-0 max-w-[62%] break-words text-right font-semibold text-ink">{value}</dd>
    </div>
  );
}
