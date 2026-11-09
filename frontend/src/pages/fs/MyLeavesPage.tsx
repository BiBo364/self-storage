import { Send } from "lucide-react";
import { useEffect, useState } from "react";
import { errorMessage, errorStatus } from "../../app/http";
import {
  addDays,
  cancelLeave,
  fetchMyLeaves,
  fetchMyProfile,
  formatDmy,
  isoDate,
  LEAVE_STATUS_LABEL,
  requestLeave,
  type StaffLeave,
  type StaffProfileSummary,
} from "../../app/services/hr";
import { StaffProfileDetails } from "../../components/domain/StaffProfileSection";
import { Button } from "../../components/ui/Button";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

type Tone = "success" | "warning" | "danger" | "info" | "neutral";

const STATUS_TONE: Record<string, Tone> = {
  Pending: "warning",
  Approved: "success",
  Rejected: "danger",
  Canceled: "neutral",
};

/** FS gửi / xem / huỷ đơn nghỉ phép, kèm hồ sơ của mình (không có số giấy tờ). */
export function MyLeavesPage() {
  const tomorrow = addDays(isoDate(new Date()), 1);
  const [fromDate, setFromDate] = useState(tomorrow);
  const [toDate, setToDate] = useState(tomorrow);
  const [reason, setReason] = useState("");
  const [leaves, setLeaves] = useState<StaffLeave[]>([]);
  const [profile, setProfile] = useState<StaffProfileSummary | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null);
  const [canceling, setCanceling] = useState<StaffLeave | null>(null);

  useEffect(() => {
    let active = true;
    Promise.all([fetchMyLeaves(), fetchMyProfile()]).then(
      ([list, mine]) => {
        if (!active) return;
        setLeaves(list);
        setProfile(mine);
        setError(null);
        setIsLoading(false);
      },
      (caught: unknown) => {
        if (!active) return;
        setError({ status: errorStatus(caught), message: errorMessage(caught) });
        setIsLoading(false);
      },
    );
    return () => {
      active = false;
    };
  }, [reloadKey]);

  function reload() {
    setReloadKey((key) => key + 1);
  }

  async function submit() {
    setBusy(true);
    setResult(null);
    try {
      const created = await requestLeave({ fromDate, toDate, reason: reason.trim() });
      setReason("");
      setResult({
        ok: true,
        message: `Đã gửi đơn nghỉ ${formatDmy(created.fromDate)} – ${formatDmy(created.toDate)}, chờ FM duyệt.`,
      });
      reload();
    } catch (caught) {
      setResult({ ok: false, message: errorMessage(caught) });
    } finally {
      setBusy(false);
    }
  }

  async function confirmCancel() {
    if (!canceling) return;
    setBusy(true);
    try {
      await cancelLeave(canceling.id);
      setResult({ ok: true, message: "Đã huỷ đơn nghỉ phép." });
      reload();
    } catch (caught) {
      setResult({ ok: false, message: errorMessage(caught) });
    } finally {
      setCanceling(null);
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Nghỉ phép &amp; hồ sơ</h1>
        <p className="mt-1.5 text-small text-muted">
          Gửi đơn nghỉ cho FM cơ sở. Đơn được duyệt thì ca trong khoảng nghỉ bị xoá; FM không giao
          việc cho bạn trong những ngày đó.
        </p>
      </div>

      <section className="grid gap-3 bg-surface p-4">
        <SectionTitle>Gửi đơn nghỉ</SectionTitle>
        <div className="flex flex-wrap items-end gap-3">
          <DateField label="Từ ngày" value={fromDate} onChange={setFromDate} />
          <DateField label="Đến ngày" value={toDate} onChange={setToDate} />
        </div>
        <label className="grid gap-1.5 text-label text-muted">
          Lý do
          <div className={INPUT_WRAP}>
            <input
              className={INPUT_ELEMENT}
              maxLength={500}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </div>
        </label>
        <div>
          <Button pending={busy} disabled={!reason.trim()} onClick={submit}>
            {!busy && <Send size={16} />} Gửi đơn
          </Button>
        </div>
        {result && <DemoNotice tone={result.ok ? "success" : "error"}>{result.message}</DemoNotice>}
      </section>

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải đơn nghỉ phép…" />
      ) : error ? (
        <SurfaceState
          variant={error.status === 403 ? "forbidden" : "error"}
          title="Không tải được đơn nghỉ phép"
          description={error.message}
          action={{ label: "Thử lại", onClick: reload }}
        />
      ) : (
        <>
          <section className="grid gap-2">
            <SectionTitle>Đơn của tôi</SectionTitle>
            {leaves.length === 0 ? (
              <SurfaceState variant="empty" title="Bạn chưa gửi đơn nghỉ nào" />
            ) : (
              leaves.map((leave) => (
                <div
                  key={leave.id}
                  className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4"
                >
                  <div className="min-w-0">
                    <strong className="block text-small text-ink">
                      {formatDmy(leave.fromDate)} – {formatDmy(leave.toDate)}
                    </strong>
                    <span className="mt-1 block text-label text-muted">{leave.reason}</span>
                    {leave.reviewNote && (
                      <span className="mt-1 block text-label text-muted">
                        Ghi chú FM: {leave.reviewNote}
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-2">
                    <StatusTag tone={STATUS_TONE[leave.status] ?? "neutral"}>
                      {LEAVE_STATUS_LABEL[leave.status] ?? leave.status}
                    </StatusTag>
                    {leave.cancelable && (
                      <Button size="sm" variant="secondary" onClick={() => setCanceling(leave)}>
                        Huỷ đơn
                      </Button>
                    )}
                  </div>
                </div>
              ))
            )}
          </section>

          {profile && (
            <section className="grid gap-3 bg-surface p-4">
              <SectionTitle>Hồ sơ của tôi</SectionTitle>
              <StaffProfileDetails profile={profile} />
            </section>
          )}
        </>
      )}

      {canceling && (
        <ConfirmDialog
          title="Huỷ đơn nghỉ phép?"
          description={`Đơn ${formatDmy(canceling.fromDate)} – ${formatDmy(canceling.toDate)}. Ca đã bị xoá lúc duyệt không tự khôi phục.`}
          confirmLabel="Huỷ đơn"
          isPending={busy}
          onConfirm={confirmCancel}
          onCancel={() => setCanceling(null)}
        />
      )}
    </div>
  );
}

function DateField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <label className="grid gap-1.5 text-label text-muted">
      {label}
      <div className={`${INPUT_WRAP} min-w-[180px]`}>
        <input
          type="date"
          className={INPUT_ELEMENT}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      </div>
    </label>
  );
}
