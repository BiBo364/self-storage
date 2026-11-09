import { useEffect, useState } from "react";
import { formatDateTime, formatMoney } from "../../app/format";
import {
  fetchInvoiceRefunds,
  recordInvoiceRefund,
  type RefundSummary,
} from "../../app/services/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { SurfaceState } from "../../components/ui/SurfaceState";

/**
 * FM ghi nhận đã chuyển khoản hoàn tiền cho khách (cổng không có API hoàn tiền). Backend chặn hoàn vượt số đã thu
 * qua cổng và tự chuyển hoá đơn sang `Processing` / `Refunded`.
 */
export function FmRefundRecorder({
  invoiceId,
  onRecorded,
}: {
  invoiceId: number;
  onRecorded: () => void;
}) {
  const [summary, setSummary] = useState<RefundSummary | null>(null);
  const [amount, setAmount] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  useEffect(() => {
    let active = true;
    fetchInvoiceRefunds(invoiceId).then(
      (result) => active && setSummary(result),
      (caught: unknown) =>
        active && setError(caught instanceof Error ? caught.message : String(caught)),
    );
    return () => {
      active = false;
    };
  }, [invoiceId]);

  async function submit() {
    const value = Number(amount);
    if (!Number.isFinite(value) || value <= 0 || !note.trim()) {
      setError("Nhập số tiền > 0 và ghi chú (lý do, mã chuyển khoản).");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setSummary(await recordInvoiceRefund(invoiceId, value, note.trim()));
      setAmount("");
      setNote("");
      setDone(true);
      onRecorded();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-4">
      <p className="m-0 text-small text-muted">
        Chuyển khoản hoàn cho khách trước, rồi ghi nhận tại đây để đối soát. Không ghi nhận vượt số
        đã thu qua cổng.
      </p>
      {done && <DemoNotice tone="success">Đã ghi nhận hoàn tiền.</DemoNotice>}
      {error && <DemoNotice tone="error">{error}</DemoNotice>}
      {!summary ? (
        !error && <SurfaceState variant="loading" title="Đang tải lịch sử hoàn tiền…" />
      ) : (
        <>
          <p className="m-0 text-small text-ink">
            Còn hoàn được: <strong>{formatMoney(summary.refundable)}</strong>
          </p>
          {summary.refunds.length > 0 && (
            <ul className="m-0 grid gap-1.5 p-0 text-label text-muted">
              {summary.refunds.map((refund) => (
                <li key={refund.id} className="list-none">
                  {formatDateTime(refund.recordedAt)} · {formatMoney(refund.amount)} ·{" "}
                  {refund.note ?? "—"}
                </li>
              ))}
            </ul>
          )}
          <label className="grid gap-1.5">
            <span className="text-label text-muted">Số tiền đã hoàn (đ)</span>
            <div className={INPUT_WRAP}>
              <input
                className={INPUT_ELEMENT}
                inputMode="numeric"
                value={amount}
                onChange={(event) => setAmount(event.target.value.replace(/[^0-9]/g, ""))}
              />
            </div>
          </label>
          <label className="grid gap-1.5">
            <span className="text-label text-muted">Ghi chú (lý do, mã chuyển khoản)</span>
            <div className={INPUT_WRAP}>
              <input
                className={INPUT_ELEMENT}
                maxLength={500}
                value={note}
                onChange={(event) => setNote(event.target.value)}
              />
            </div>
          </label>
          <div>
            <Button pending={busy} onClick={submit} disabled={summary.refundable <= 0}>
              Ghi nhận đã hoàn
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
