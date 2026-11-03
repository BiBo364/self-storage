import { AlertTriangle, ArrowRight, Check, MapPin, X } from "lucide-react";
import { useState } from "react";
import type { Navigate } from "../app/types";
import { rentedStorage } from "../mocks/customer";
import { MAX_REJECTIONS, proposals as initialProposals, type Proposal } from "../mocks/proposals";
import { Button } from "../components/ui/Button";
import { DemoNotice } from "../components/ui/DemoNotice";
import { SurfaceState } from "../components/ui/SurfaceState";

type Tab = "active" | "pending";

export function ProposalsPage({ navigate }: { navigate: Navigate }) {
  const [tab, setTab] = useState<Tab>("pending");
  const [items, setItems] = useState<Proposal[]>(initialProposals);
  const [agreedIds, setAgreedIds] = useState<Set<string>>(new Set());
  const [canceledIds, setCanceledIds] = useState<Set<string>>(new Set());
  const [conflictId, setConflictId] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [rejectingId, setRejectingId] = useState<string | null>(null);
  const [rejectNote, setRejectNote] = useState("");

  const visibleProposals = items.filter((proposal) => !canceledIds.has(proposal.id));
  const awaitingCount = items.filter(
    (proposal) => !agreedIds.has(proposal.id) && !canceledIds.has(proposal.id),
  ).length;

  function agree(proposal: Proposal) {
    setBusyId(proposal.id);
    setConflictId(null);
    window.setTimeout(() => {
      setBusyId(null);
      if (proposal.simulateConflict) {
        setConflictId(proposal.id);
        setItems((current) =>
          current.map((item) => (item.id === proposal.id ? { ...item, status: "expired" } : item)),
        );
        return;
      }
      setAgreedIds((current) => new Set(current).add(proposal.id));
    }, 700);
  }

  function confirmReject(proposal: Proposal) {
    const nextCount = proposal.rejectionCount + 1;
    if (nextCount >= MAX_REJECTIONS) {
      setCanceledIds((current) => new Set(current).add(proposal.id));
    } else {
      setItems((current) =>
        current.map((item) =>
          item.id === proposal.id ? { ...item, rejectionCount: nextCount } : item,
        ),
      );
    }
    setRejectingId(null);
    setRejectNote("");
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

      {tab === "active" &&
        (rentedStorage.length === 0 ? (
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
        ))}

      {tab === "pending" &&
        (visibleProposals.length === 0 ? (
          <SurfaceState
            variant="empty"
            title="Chưa có đề xuất nào đang chờ"
            description="Khi đội ngũ vận hành đề xuất khoang cho yêu cầu của bạn, đề xuất sẽ hiện ở đây."
          />
        ) : (
          <div className="grid gap-2.5">
            {visibleProposals.map((proposal) => {
              const isExpired = proposal.status === "expired";
              const isAgreed = agreedIds.has(proposal.id);
              const isBusy = busyId === proposal.id;
              const nearLimit = proposal.rejectionCount >= MAX_REJECTIONS - 1;
              return (
                <article key={proposal.id} className="bg-surface p-5">
                  <div className="flex items-start justify-between gap-5 max-[760px]:flex-col">
                    <div>
                      <span className="text-label text-muted">
                        Khoang {proposal.unitCode} · {proposal.unitType}
                      </span>
                      <h2 className="mt-1 text-section text-ink">{proposal.facility}</h2>
                      <p className="mt-1.5 flex items-center gap-1.5 text-small text-muted">
                        <MapPin size={15} /> {proposal.district} · {proposal.size}
                      </p>
                    </div>
                    <div className="grid gap-3 text-right max-[760px]:text-left">
                      <div>
                        <span className="text-label text-muted">Giá thuê đề xuất</span>
                        <strong className="mt-0.5 block text-[18px] font-semibold text-ink">
                          {proposal.monthlyPrice}
                        </strong>
                      </div>
                      <div>
                        <span className="text-label text-muted">
                          {isExpired ? "Đã hết hạn" : "Hạn phản hồi"}
                        </span>
                        <strong
                          className={`mt-0.5 block text-small font-semibold ${
                            isExpired ? "text-danger" : "text-ink"
                          }`}
                        >
                          {proposal.expiresAt}
                        </strong>
                      </div>
                    </div>
                  </div>

                  {conflictId === proposal.id && (
                    <div className="mt-4">
                      <DemoNotice tone="error">
                        Khoang này vừa có người khác đặt cọc trước. Đề xuất đã hết hiệu lực — đội
                        ngũ vận hành sẽ gửi đề xuất khoang khác cho bạn.
                      </DemoNotice>
                    </div>
                  )}

                  {isExpired && conflictId !== proposal.id && (
                    <div className="mt-4 bg-surface-subtle p-4">
                      <p className="flex items-center gap-2 text-small font-semibold text-ink">
                        <AlertTriangle size={16} className="text-warning" /> Đề xuất đã hết hạn
                      </p>
                      <p className="mt-1.5 text-label text-muted">
                        Bạn chưa phản hồi trước hạn. Đội ngũ vận hành sẽ gửi đề xuất khoang mới.
                      </p>
                    </div>
                  )}

                  {isAgreed && (
                    <div className="mt-4 bg-surface-subtle p-4">
                      <DemoNotice tone="success">
                        Đã đồng ý khoang này. Hoá đơn đặt cọc đang được tạo.
                      </DemoNotice>
                      <Button
                        variant="secondary"
                        size="sm"
                        className="mt-3"
                        onClick={() => navigate("/invoices")}
                      >
                        Xem hoá đơn <ArrowRight size={15} />
                      </Button>
                    </div>
                  )}

                  {!isExpired && !isAgreed && nearLimit && (
                    <div className="mt-4">
                      <DemoNotice tone="pending">
                        Bạn đã từ chối {proposal.rejectionCount}/{MAX_REJECTIONS} lần cho phép. Từ
                        chối thêm {MAX_REJECTIONS - proposal.rejectionCount} lần nữa, yêu cầu này sẽ
                        tự huỷ và bạn cần gửi lại từ đầu.
                      </DemoNotice>
                    </div>
                  )}

                  {!isExpired && !isAgreed && (
                    <div className="mt-5 flex flex-wrap items-center gap-2.5">
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

                  {rejectingId === proposal.id && (
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
                        <Button onClick={() => confirmReject(proposal)}>Xác nhận từ chối</Button>
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

            {canceledIds.size > 0 && (
              <div className="bg-danger-soft p-4">
                <p className="flex items-center gap-2 text-small font-semibold text-danger">
                  <AlertTriangle size={16} /> Yêu cầu đã bị huỷ
                </p>
                <p className="mt-1.5 text-label text-muted">
                  Bạn đã từ chối đủ {MAX_REJECTIONS} lần nên yêu cầu này tự động huỷ. Vui lòng gửi
                  yêu cầu mới nếu vẫn cần thuê kho.
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
    </div>
  );
}
