import { FileImage, ReceiptText, ShieldCheck, Upload } from "lucide-react";
import { useEffect, useState } from "react";
import { ApiError } from "../../app/api";
import {
  loadPendingCases,
  type ReconciliationView,
  reconcilePayment,
  type PendingPaymentCase,
  type PendingPaymentPage,
  type ReconcileOutcome,
} from "../../app/adminReconciliation";
import { attachMediaBatch, uploadMedia } from "../../app/media";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";
import { formatPrice } from "../../mocks/catalog";

function formatDateTime(value: string | null) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("vi-VN", { dateStyle: "short", timeStyle: "short" }).format(
    new Date(value),
  );
}

function localDateTimeNow() {
  const now = new Date();
  now.setMinutes(now.getMinutes() - now.getTimezoneOffset());
  return now.toISOString().slice(0, 16);
}

export function PaymentReconciliationPage() {
  const [view, setView] = useState<ReconciliationView>("open");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PendingPaymentPage>({
    items: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ tone: "success" | "error"; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [selected, setSelected] = useState<PendingPaymentCase | null>(null);
  const [outcome, setOutcome] = useState<ReconcileOutcome>("SUCCESS");
  const [note, setNote] = useState("");
  const [paidAt, setPaidAt] = useState(localDateTimeNow);
  const [gatewayNo, setGatewayNo] = useState("");
  const [evidenceUrl, setEvidenceUrl] = useState("");
  const [uploading, setUploading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    loadPendingCases(page, 20, view)
      .then((result) => {
        if (active) setData(result);
      })
      .catch((reason: unknown) => {
        if (!active) return;
        setError(
          reason instanceof ApiError || reason instanceof Error
            ? reason.message
            : "Không tải được danh sách case.",
        );
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [page, reloadKey, view]);

  function reload() {
    setLoading(true);
    setError(null);
    setReloadKey((key) => key + 1);
  }

  function changeView(next: ReconciliationView) {
    if (next === view) return;
    setView(next);
    setPage(0);
    setSelected(null);
    setLoading(true);
    setError(null);
  }

  function openCase(item: PendingPaymentCase) {
    setSelected(item);
    setOutcome("SUCCESS");
    setNote("");
    setPaidAt(localDateTimeNow());
    setGatewayNo("");
    setEvidenceUrl(item.evidenceUrl ?? "");
    setFormError(null);
  }

  async function uploadEvidence(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setUploading(true);
    setFormError(null);
    try {
      const uploaded = await uploadMedia(file);
      await attachMediaBatch(uploaded.batchId);
      setEvidenceUrl(uploaded.url);
    } catch (reason) {
      setFormError(reason instanceof Error ? reason.message : "Upload bằng chứng thất bại.");
    } finally {
      setUploading(false);
      event.target.value = "";
    }
  }

  async function submit() {
    if (!selected) return;
    if (!note.trim()) {
      setFormError("Vui lòng nhập ghi chú đối soát.");
      return;
    }
    if (outcome === "SUCCESS" && !gatewayNo.trim()) {
      setFormError("SUCCESS cần gatewayTransactionNo.");
      return;
    }
    if (outcome === "SUCCESS" && !paidAt) {
      setFormError("SUCCESS cần paidAt.");
      return;
    }
    setSubmitting(true);
    setFormError(null);
    try {
      await reconcilePayment(selected.vnpTxnRef, {
        outcome,
        note: note.trim(),
        paidAt: outcome === "SUCCESS" ? new Date(paidAt).toISOString() : null,
        gatewayTransactionNo: outcome === "SUCCESS" ? gatewayNo.trim() : null,
        evidenceUrl: evidenceUrl.trim() || null,
      });
      setSelected(null);
      setNotice({ tone: "success", message: "Đã đối soát giao dịch." });
      reload();
    } catch (reason) {
      setFormError(
        reason instanceof ApiError || reason instanceof Error
          ? reason.message
          : "Đối soát thất bại.",
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Đối soát thanh toán</h1>
        <p className="mt-1.5 text-small text-muted">
          Case Pending quá 15 phút và lịch sử đã đối soát. Tra portal VNPay, đính kèm bằng chứng và
          chốt kết quả.
        </p>
      </div>

      <div className="flex gap-2 border-b border-rule" role="tablist" aria-label="Đối soát">
        <button
          type="button"
          role="tab"
          aria-selected={view === "open"}
          className={`border-b-2 px-3 py-2 text-small font-semibold ${view === "open" ? "border-accent text-accent" : "border-transparent text-muted hover:text-ink"}`}
          onClick={() => changeView("open")}
        >
          Cần đối soát
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={view === "history"}
          className={`border-b-2 px-3 py-2 text-small font-semibold ${view === "history" ? "border-accent text-accent" : "border-transparent text-muted hover:text-ink"}`}
          onClick={() => changeView("history")}
        >
          Lịch sử
        </button>
      </div>

      {notice && <DemoNotice tone={notice.tone}>{notice.message}</DemoNotice>}

      <div>
        <h2 className="m-0 text-section text-ink">
          {view === "history" ? "Case đã đối soát" : "Case Pending cần đối soát"}
        </h2>
        <p className="mt-1.5 text-small text-muted">
          {view === "history"
            ? "Các case đã chốt kết quả, chỉ đọc để tra cứu."
            : "Giao dịch Pending quá 15 phút chưa được cổng xác nhận."}
        </p>
      </div>

      {loading ? (
        <SurfaceState
          variant="loading"
          title={view === "history" ? "Đang tải lịch sử đối soát" : "Đang tải case Pending"}
        />
      ) : error ? (
        <SurfaceState
          variant="error"
          title={
            view === "history" ? "Không tải được lịch sử đối soát" : "Không tải được case Pending"
          }
          description={error}
          action={{ label: "Thử lại", onClick: reload }}
        />
      ) : data.items.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={
            view === "history"
              ? "Chưa có case đối soát nào đã xử lý"
              : "Không có giao dịch Pending nào cần đối soát"
          }
        />
      ) : (
        <div className="grid gap-2">
          {data.items.map((item) => (
            <button
              key={item.caseId}
              className="grid grid-cols-[minmax(0,1.5fr)_minmax(0,0.8fr)_auto] items-center gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle max-[760px]:grid-cols-1 max-[760px]:gap-2"
              onClick={() => openCase(item)}
            >
              <div>
                <span className="flex items-center gap-2 text-label text-muted">
                  <ReceiptText size={14} /> {item.vnpTxnRef}
                </span>
                <strong className="mt-1 block text-small font-semibold text-ink">
                  Invoice #{item.invoiceId}
                  {view === "history"
                    ? ` · ${item.resolution ?? "Đã xử lý"}`
                    : ` · Pending ${item.pendingAgeMinutes} phút`}
                </strong>
                <span className="mt-1 block text-label text-muted">
                  {view === "history"
                    ? `Xử lý ${formatDateTime(item.resolvedAt)}`
                    : `Phát hiện ${formatDateTime(item.detectedAt)}`}
                </span>
              </div>
              <strong className="text-[18px] font-semibold text-ink">
                {formatPrice(item.amount)} đ
              </strong>
              {view === "history" ? (
                <StatusTag tone="neutral">{item.status}</StatusTag>
              ) : (
                <StatusTag tone="danger">Cần đối soát</StatusTag>
              )}
            </button>
          ))}
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <span className="text-label text-muted">
          Trang {Math.min(page + 1, Math.max(1, data.totalPages))} / {Math.max(1, data.totalPages)}{" "}
          · {data.totalElements} case
        </span>
        <div className="flex gap-2">
          <Button
            variant="secondary"
            disabled={loading || page === 0}
            onClick={() => {
              setLoading(true);
              setPage((current) => current - 1);
            }}
          >
            Trang trước
          </Button>
          <Button
            variant="secondary"
            disabled={loading || page + 1 >= data.totalPages}
            onClick={() => {
              setLoading(true);
              setPage((current) => current + 1);
            }}
          >
            Trang sau
          </Button>
        </div>
      </div>

      {selected && view === "history" && (
        <DetailPanel
          title={selected.vnpTxnRef}
          eyebrow={`Invoice #${selected.invoiceId} · ${selected.status}`}
          onClose={() => setSelected(null)}
        >
          <div className="grid grid-cols-2 gap-3 bg-surface-subtle p-3.5 text-small">
            <span className="text-muted">Số tiền</span>
            <strong className="text-right text-ink">{formatPrice(selected.amount)} đ</strong>
            <span className="text-muted">Kết quả</span>
            <strong className="text-right text-ink">{selected.resolution ?? "—"}</strong>
            <span className="text-muted">Phát hiện</span>
            <strong className="text-right text-ink">{formatDateTime(selected.detectedAt)}</strong>
            <span className="text-muted">Xử lý lúc</span>
            <strong className="text-right text-ink">{formatDateTime(selected.resolvedAt)}</strong>
            <span className="text-muted">Người xử lý</span>
            <strong className="break-all text-right text-ink">
              {selected.resolvedByEmail ?? "—"}
            </strong>
            <span className="text-muted">Mã VNPay</span>
            <strong className="break-all text-right text-ink">
              {selected.gatewayTransactionNo ?? "—"}
            </strong>
            <span className="text-muted">Đã thanh toán</span>
            <strong className="text-right text-ink">{formatDateTime(selected.paidAt)}</strong>
          </div>
          <p className="mt-4 whitespace-pre-wrap text-small leading-relaxed text-muted">
            {selected.note || "Không có ghi chú đối soát."}
          </p>
          {selected.evidenceUrl && (
            <a
              className="mt-3 block break-all text-label text-accent underline"
              href={selected.evidenceUrl}
              target="_blank"
              rel="noreferrer"
            >
              Bằng chứng đối soát
            </a>
          )}
        </DetailPanel>
      )}

      {selected && view === "open" && (
        <DetailPanel
          title={selected.vnpTxnRef}
          eyebrow={`Invoice #${selected.invoiceId} · Pending ${selected.pendingAgeMinutes} phút`}
          onClose={() => setSelected(null)}
        >
          <div className="grid gap-4">
            <div className="grid grid-cols-2 gap-3 bg-surface-subtle p-3.5 text-small">
              <span className="text-muted">Số tiền</span>
              <strong className="text-right text-ink">{formatPrice(selected.amount)} đ</strong>
              <span className="text-muted">Phát hiện</span>
              <strong className="text-right text-ink">{formatDateTime(selected.detectedAt)}</strong>
              <span className="text-muted">Giao dịch tạo</span>
              <strong className="text-right text-ink">
                {formatDateTime(selected.transactionCreatedAt)}
              </strong>
            </div>

            <label className="grid gap-1.5">
              <span className="text-label text-muted">Kết quả portal</span>
              <select
                className="min-h-[40px] border border-rule-strong bg-surface px-3 text-small text-ink"
                value={outcome}
                onChange={(event) => setOutcome(event.target.value as ReconcileOutcome)}
              >
                <option value="SUCCESS">Đã thanh toán — SUCCESS</option>
                <option value="FAILED">Chưa thanh toán — FAILED</option>
              </select>
            </label>

            {outcome === "SUCCESS" && (
              <>
                <label className="grid gap-1.5">
                  <span className="text-label text-muted">paidAt</span>
                  <input
                    type="datetime-local"
                    className="min-h-[40px] border border-rule-strong bg-surface px-3 text-small text-ink"
                    value={paidAt}
                    onChange={(event) => setPaidAt(event.target.value)}
                  />
                </label>
                <label className="grid gap-1.5">
                  <span className="text-label text-muted">gatewayTransactionNo</span>
                  <input
                    className="min-h-[40px] border border-rule-strong bg-surface px-3 text-small text-ink"
                    value={gatewayNo}
                    onChange={(event) => setGatewayNo(event.target.value)}
                    placeholder="Mã giao dịch VNPay"
                  />
                </label>
              </>
            )}

            <label className="grid gap-1.5">
              <span className="text-label text-muted">Ghi chú đối soát *</span>
              <textarea
                className="min-h-20 border border-rule-strong bg-surface px-3 py-2 text-small text-ink"
                value={note}
                onChange={(event) => setNote(event.target.value)}
                placeholder="Ví dụ: portal VNPay ngày ... giao dịch ..."
              />
            </label>

            <div className="grid gap-2">
              <span className="flex items-center gap-1.5 text-label text-muted">
                <FileImage size={14} /> Bằng chứng đối soát (không bắt buộc)
              </span>
              <p className="m-0 text-label text-muted">
                Ảnh/PDF chụp portal hoặc màn hình tra cứu. Không thay thế mã giao dịch/số tiền.
              </p>
              <label className="inline-flex w-fit cursor-pointer items-center gap-2 border border-rule-strong px-3 py-2 text-label font-semibold text-ink hover:bg-surface-subtle">
                <Upload size={14} /> {uploading ? "Đang upload…" : "Chọn ảnh/PDF"}
                <input
                  type="file"
                  accept="image/*,.pdf"
                  className="hidden"
                  onChange={uploadEvidence}
                  disabled={uploading}
                />
              </label>
              {evidenceUrl ? (
                <div className="grid gap-2">
                  <a
                    className="break-all text-label text-accent underline"
                    href={evidenceUrl}
                    target="_blank"
                    rel="noreferrer"
                  >
                    {evidenceUrl}
                  </a>
                  {!evidenceUrl.toLowerCase().includes(".pdf") && (
                    <img
                      src={evidenceUrl}
                      alt="Bằng chứng đối soát"
                      className="max-h-40 w-fit border border-rule-strong object-contain"
                    />
                  )}
                </div>
              ) : (
                <span className="text-label text-muted">Không có bằng chứng đính kèm</span>
              )}
            </div>

            {formError && <DemoNotice tone="error">{formError}</DemoNotice>}

            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setSelected(null)}>
                Huỷ
              </Button>
              <Button pending={submitting} onClick={submit}>
                <ShieldCheck size={16} /> Xác nhận đối soát
              </Button>
            </div>
          </div>
        </DetailPanel>
      )}
    </div>
  );
}
