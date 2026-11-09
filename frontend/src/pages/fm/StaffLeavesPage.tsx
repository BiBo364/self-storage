import { Check, X } from "lucide-react";
import { useEffect, useState } from "react";
import { formatDateTime } from "../../app/format";
import { fetchCurrentFmFacility } from "../../app/fmFacility";
import { errorMessage, errorStatus } from "../../app/http";
import {
  approveLeave,
  fetchFacilityLeaves,
  formatDmy,
  LEAVE_STATUS_LABEL,
  rejectLeave,
  type StaffLeave,
} from "../../app/services/hr";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

type Tone = "success" | "warning" | "danger" | "info" | "neutral";

const STATUS_TONE: Record<string, Tone> = {
  Pending: "warning",
  Approved: "success",
  Rejected: "danger",
  Canceled: "neutral",
};

const TABS = [
  { value: "Pending", label: "Chờ duyệt" },
  { value: "", label: "Lịch sử" },
];

function staffName(leave: StaffLeave) {
  return leave.staffName ?? leave.staffEmail;
}

/** FM duyệt / từ chối đơn nghỉ phép của FS thuộc cơ sở. FS còn việc trong khoảng nghỉ → backend trả 409 kèm danh sách. */
export function StaffLeavesPage() {
  const [tab, setTab] = useState("Pending");
  const [leaves, setLeaves] = useState<StaffLeave[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const facility = await fetchCurrentFmFacility();
        const list = await fetchFacilityLeaves(facility.id, tab || undefined);
        if (!active) return;
        setLeaves(list);
        setError(null);
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [tab, reloadKey]);

  function reload() {
    setIsLoading(true);
    setReloadKey((key) => key + 1);
  }

  const openLeave = leaves.find((leave) => leave.id === openId) ?? null;

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Nghỉ phép</h1>
        <p className="mt-1.5 text-small text-muted">
          Duyệt đơn nghỉ của FS thuộc cơ sở. Duyệt xong, ca của FS trong khoảng nghỉ được xoá.
        </p>
      </div>

      <div className="flex flex-wrap gap-1.5" role="tablist" aria-label="Lọc đơn nghỉ">
        {TABS.map((item) => (
          <button
            key={item.label}
            role="tab"
            aria-selected={tab === item.value}
            className={`border-0 px-2.5 py-1.5 text-small font-semibold ${
              tab === item.value
                ? "bg-brand text-signal-ink"
                : "bg-transparent text-muted hover:bg-surface-subtle hover:text-ink"
            }`}
            onClick={() => {
              setIsLoading(true);
              setNotice(null);
              setTab(item.value);
            }}
          >
            {item.label}
          </button>
        ))}
      </div>

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải đơn nghỉ phép…" />
      ) : error ? (
        error.status === 403 || error.status === 404 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền duyệt nghỉ phép"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được đơn nghỉ phép"
            description={error.message}
            action={{ label: "Thử lại", onClick: reload }}
          />
        )
      ) : leaves.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={tab === "Pending" ? "Không có đơn chờ duyệt" : "Chưa có đơn nghỉ phép"}
        />
      ) : (
        <div className="grid gap-2">
          {leaves.map((leave) => (
            <button
              key={leave.id}
              className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
              onClick={() => {
                setNotice(null);
                setOpenId(leave.id);
              }}
            >
              <div className="min-w-0">
                <strong className="block text-small font-semibold text-ink">
                  {staffName(leave)}
                </strong>
                <span className="mt-1 block text-label text-muted">
                  {formatDmy(leave.fromDate)} – {formatDmy(leave.toDate)} · {leave.reason}
                </span>
              </div>
              <StatusTag tone={STATUS_TONE[leave.status] ?? "neutral"}>
                {LEAVE_STATUS_LABEL[leave.status] ?? leave.status}
              </StatusTag>
            </button>
          ))}
        </div>
      )}

      {openLeave && (
        <ReviewPanel
          key={openLeave.id}
          leave={openLeave}
          onClose={() => setOpenId(null)}
          onReviewed={(result) => {
            setOpenId(null);
            setNotice(
              `Đơn nghỉ của ${staffName(result)} ${result.status === "Approved" ? "đã được duyệt" : "đã bị từ chối"}.`,
            );
            reload();
          }}
        />
      )}
    </div>
  );
}

function ReviewPanel({
  leave,
  onClose,
  onReviewed,
}: {
  leave: StaffLeave;
  onClose: () => void;
  onReviewed: (leave: StaffLeave) => void;
}) {
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState<"approve" | "reject" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const pending = leave.status === "Pending";

  async function review(kind: "approve" | "reject") {
    setBusy(kind);
    setError(null);
    try {
      onReviewed(
        kind === "approve"
          ? await approveLeave(leave.id, note.trim())
          : await rejectLeave(leave.id, note.trim()),
      );
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(null);
    }
  }

  return (
    <DetailPanel
      title={staffName(leave)}
      eyebrow={`Đơn nghỉ #${leave.id}`}
      onClose={onClose}
      footer={
        pending ? (
          <>
            <Button
              variant="danger"
              pending={busy === "reject"}
              disabled={!!busy}
              onClick={() => review("reject")}
            >
              {busy !== "reject" && <X size={16} />} Từ chối
            </Button>
            <Button
              pending={busy === "approve"}
              disabled={!!busy}
              onClick={() => review("approve")}
            >
              {busy !== "approve" && <Check size={16} />} Duyệt
            </Button>
          </>
        ) : undefined
      }
    >
      <div className="grid gap-2 text-small text-muted">
        <span>{leave.staffEmail}</span>
        <span>
          Nghỉ từ {formatDmy(leave.fromDate)} đến {formatDmy(leave.toDate)}
        </span>
        <span>Lý do: {leave.reason}</span>
        <span>
          Trạng thái:{" "}
          <StatusTag tone={STATUS_TONE[leave.status] ?? "neutral"}>
            {LEAVE_STATUS_LABEL[leave.status] ?? leave.status}
          </StatusTag>
        </span>
        {leave.reviewedByEmail && (
          <span>
            Người duyệt: {leave.reviewedByEmail}
            {leave.reviewedAt ? ` · ${formatDateTime(leave.reviewedAt)}` : ""}
          </span>
        )}
        {leave.reviewNote && <span>Ghi chú: {leave.reviewNote}</span>}
      </div>
      {pending && (
        <label className="mt-4 grid gap-1.5 text-label text-muted">
          Ghi chú cho FS (không bắt buộc)
          <div className={INPUT_WRAP}>
            <input
              className={INPUT_ELEMENT}
              maxLength={500}
              value={note}
              onChange={(event) => setNote(event.target.value)}
            />
          </div>
        </label>
      )}
      {error && <DemoNotice tone="error">{error}</DemoNotice>}
    </DetailPanel>
  );
}
