import { AlertTriangle, ArrowRight, Check, MapPin, X } from "lucide-react";
import { useState } from "react";
import type { Navigate } from "../app/types";
import { formatDateTime, formatMoney } from "../app/format";
import { errorMessage } from "../app/http";
import {
  agreeProposal,
  fetchProposals,
  PROPOSAL_STATUS,
  rejectProposal,
  useResource,
  type CustomerProposal,
} from "../app/services/customer";
import { Button } from "../components/ui/Button";
import { DemoNotice } from "../components/ui/DemoNotice";
import { StatusTag } from "../components/ui/StatusTag";
import { SurfaceState } from "../components/ui/SurfaceState";

type Tab = "pending" | "history";

type Outcome =
  | { kind: "agreed"; proposalId: number; hasInvoice: boolean }
  | { kind: "rejected"; proposalId: number; orderCanceled: boolean }
  | { kind: "error"; proposalId: number; message: string };

export function ProposalsPage({ navigate }: { navigate: Navigate }) {
  const proposals = useResource(fetchProposals);
  const [tab, setTab] = useState<Tab>("pending");
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rejectingId, setRejectingId] = useState<number | null>(null);
  const [rejectNote, setRejectNote] = useState("");
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  const items = proposals.data ?? [];
  const pending = items.filter((proposal) => proposal.status === "Pending");
  const history = items.filter((proposal) => proposal.status !== "Pending");
  const visible = tab === "pending" ? pending : history;

  async function agree(proposal: CustomerProposal) {
    setBusyId(proposal.id);
    setOutcome(null);
    try {
      const result = await agreeProposal(proposal.id);
      setOutcome({ kind: "agreed", proposalId: proposal.id, hasInvoice: result.invoice !== null });
    } catch (caught) {
      setOutcome({ kind: "error", proposalId: proposal.id, message: errorMessage(caught) });
    } finally {
      setBusyId(null);
      proposals.reload();
    }
  }

  async function confirmReject(proposal: CustomerProposal) {
    setBusyId(proposal.id);
    setOutcome(null);
    try {
      const result = await rejectProposal(proposal.id, rejectNote.trim());
      setOutcome({
        kind: "rejected",
        proposalId: proposal.id,
        orderCanceled: result.orderCanceled,
      });
      setRejectingId(null);
      setRejectNote("");
      proposals.reload();
    } catch (caught) {
      setOutcome({ kind: "error", proposalId: proposal.id, message: errorMessage(caught) });
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

  function countClass(active: boolean) {
    return `px-1.5 text-label ${active ? "bg-signal-ink text-brand" : "bg-surface text-muted"}`;
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Đề xuất khoang</h1>
        <p className="mt-1.5 text-small text-muted">
          Xem lại khoang cơ sở đề xuất cho yêu cầu của bạn và xác nhận trước khi đặt cọc.
        </p>
      </div>

      <div className="flex flex-wrap gap-1.5">
        <button className={tabClass(tab === "pending")} onClick={() => setTab("pending")}>
          Chờ phản hồi
          <span className={countClass(tab === "pending")}>{pending.length}</span>
        </button>
        <button className={tabClass(tab === "history")} onClick={() => setTab("history")}>
          Đã xử lý
          <span className={countClass(tab === "history")}>{history.length}</span>
        </button>
      </div>

      {outcome &&
        (outcome.kind === "rejected" ||
          !visible.some((proposal) => proposal.id === outcome.proposalId)) && (
          <OutcomeNotice outcome={outcome} navigate={navigate} />
        )}

      {proposals.loading && !proposals.data ? (
        <div className="grid gap-2.5" role="status" aria-label="Đang tải">
          {[0, 1].map((index) => (
            <div key={index} className="h-[150px] animate-pulse bg-surface" />
          ))}
        </div>
      ) : proposals.error && !proposals.data ? (
        <SurfaceState
          variant="error"
          title="Không tải được đề xuất khoang"
          description={proposals.error}
          action={{ label: "Thử lại", onClick: proposals.reload }}
        />
      ) : visible.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={tab === "pending" ? "Chưa có đề xuất nào đang chờ" : "Chưa có đề xuất đã xử lý"}
          description={
            tab === "pending"
              ? "Khi cơ sở đề xuất khoang cho yêu cầu của bạn, đề xuất sẽ hiện ở đây."
              : undefined
          }
          action={
            tab === "pending"
              ? { label: "Về Kho của tôi", onClick: () => navigate("/my-storage") }
              : undefined
          }
        />
      ) : (
        <div className="grid gap-2.5">
          {visible.map((proposal) => {
            const status = PROPOSAL_STATUS[proposal.status] ?? {
              label: proposal.status,
              tone: "neutral" as const,
            };
            const isPending = proposal.status === "Pending";
            const isBusy = busyId === proposal.id;
            const lastRejection = proposal.rejectionCount + 1 >= proposal.maxRejectionCount;
            const myOutcome = outcome?.proposalId === proposal.id ? outcome : null;
            return (
              <article key={proposal.id} className="bg-surface p-5">
                <div className="flex items-start justify-between gap-5 max-[760px]:flex-col">
                  <div>
                    <span className="text-label text-muted">
                      Đơn #{proposal.orderId} · Khoang {proposal.unitCode}
                      {proposal.unitTypeName ? ` · ${proposal.unitTypeName}` : ""}
                      {proposal.unitSize ? ` · ${proposal.unitSize}` : ""}
                    </span>
                    <h2 className="mt-1 text-section text-ink">{proposal.facilityName}</h2>
                    {proposal.facilityAddress && (
                      <p className="mt-1.5 flex items-center gap-1.5 text-small text-muted">
                        <MapPin size={15} /> {proposal.facilityAddress}
                      </p>
                    )}
                    <p className="mt-1.5 text-label text-muted">
                      Đề xuất lúc {formatDateTime(proposal.createdAt)}
                    </p>
                  </div>
                  <div className="grid gap-3 text-right max-[760px]:text-left">
                    <div>
                      <StatusTag tone={status.tone}>{status.label}</StatusTag>
                    </div>
                    <div>
                      <span className="text-label text-muted">Giá thuê đề xuất</span>
                      <strong className="mt-0.5 block text-[18px] font-semibold text-ink">
                        {formatMoney(proposal.monthlyPrice)}
                        <span className="ml-1 text-label font-normal text-muted">/ tháng</span>
                      </strong>
                    </div>
                  </div>
                </div>

                {proposal.note && (
                  <p className="mt-4 bg-surface-subtle p-3 text-small text-muted">
                    Lý do từ chối: {proposal.note}
                  </p>
                )}

                {myOutcome && myOutcome.kind !== "rejected" && (
                  <OutcomeNotice outcome={myOutcome} navigate={navigate} />
                )}

                {isPending && lastRejection && (
                  <DemoNotice tone="pending">
                    Bạn đã từ chối {proposal.rejectionCount}/{proposal.maxRejectionCount} lần cho
                    phép. Nếu từ chối đề xuất này, đơn sẽ bị huỷ và bạn cần gửi yêu cầu mới.
                  </DemoNotice>
                )}

                {isPending && rejectingId !== proposal.id && (
                  <div className="mt-5 flex flex-wrap items-center gap-2.5">
                    <Button pending={isBusy} onClick={() => agree(proposal)}>
                      {!isBusy && <Check size={16} />}
                      Đồng ý
                    </Button>
                    <Button
                      variant="secondary"
                      disabled={isBusy}
                      onClick={() => {
                        setRejectingId(proposal.id);
                        setRejectNote("");
                        setOutcome(null);
                      }}
                    >
                      <X size={16} /> Từ chối
                    </Button>
                  </div>
                )}

                {isPending && rejectingId === proposal.id && (
                  <div className="mt-4 bg-surface-subtle p-4">
                    <label className="grid gap-1.5 text-label text-muted">
                      Lý do từ chối *
                      <textarea
                        className="min-h-[70px] rounded-[2px] border border-rule-strong bg-surface p-2.5 text-small font-normal text-ink"
                        value={rejectNote}
                        onChange={(event) => setRejectNote(event.target.value)}
                        placeholder="Cho cơ sở biết vì sao khoang này chưa phù hợp…"
                      />
                    </label>
                    <div className="mt-3 flex items-center gap-2.5">
                      <Button
                        variant={lastRejection ? "danger" : "primary"}
                        pending={isBusy}
                        disabled={!rejectNote.trim()}
                        onClick={() => confirmReject(proposal)}
                      >
                        {lastRejection ? "Từ chối và huỷ đơn" : "Xác nhận từ chối"}
                      </Button>
                      <Button
                        variant="secondary"
                        disabled={isBusy}
                        onClick={() => {
                          setRejectingId(null);
                          setRejectNote("");
                        }}
                      >
                        Huỷ
                      </Button>
                    </div>
                  </div>
                )}
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}

function OutcomeNotice({ outcome, navigate }: { outcome: Outcome; navigate: Navigate }) {
  if (outcome.kind === "error") {
    return <DemoNotice tone="error">{outcome.message}</DemoNotice>;
  }
  if (outcome.kind === "agreed") {
    return (
      <div className="bg-surface-subtle p-4">
        <p className="flex items-center gap-2 text-small font-semibold text-success">
          <Check size={16} /> Đã đồng ý khoang.
          {outcome.hasInvoice ? " Vui lòng thanh toán hoá đơn đặt cọc." : ""}
        </p>
        <Button
          variant="secondary"
          size="sm"
          className="mt-3"
          onClick={() => navigate(outcome.hasInvoice ? "/invoices" : "/my-storage")}
        >
          {outcome.hasInvoice ? "Xem hoá đơn" : "Về Kho của tôi"} <ArrowRight size={15} />
        </Button>
      </div>
    );
  }
  if (outcome.orderCanceled) {
    return (
      <div className="bg-danger-soft p-4">
        <p className="flex items-center gap-2 text-small font-semibold text-danger">
          <AlertTriangle size={16} /> Đơn đã bị huỷ do từ chối quá số khoang cho phép
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
    );
  }
  return (
    <DemoNotice tone="success">
      Đã từ chối đề xuất. Cơ sở sẽ gửi đề xuất khoang khác cho bạn.
    </DemoNotice>
  );
}
