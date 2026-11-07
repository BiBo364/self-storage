import { Search } from "lucide-react";
import { useEffect, useState } from "react";
import { formatDate, formatDateTime, formatMoney } from "../../app/format";
import {
  fetchFacilityInvoices,
  fetchInvoiceRefunds,
  recordInvoiceRefund,
  type RefundSummary,
  INVOICE_STATUS_LABEL,
  INVOICE_STATUS_TONE,
  INVOICE_TYPE_LABEL,
  labelOf,
  loadErrorState,
  toneOf,
  useFmLoad,
} from "../../app/services/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const TABLE_COLUMNS = "grid-cols-[1.3fr_0.8fr_1.2fr_0.9fr_0.8fr_0.9fr]";

export function FacilityInvoicesPage() {
  const [status, setStatus] = useState("");
  const [type, setType] = useState("");
  const [search, setSearch] = useState("");
  const [refundFor, setRefundFor] = useState<{ id: number; code: string } | null>(null);

  const invoices = useFmLoad(`invoices:${status}:${type}`, (facility) =>
    fetchFacilityInvoices(facility.id, { status, type }),
  );

  const term = search.trim().toLowerCase();
  const list = (invoices.data ?? []).filter((invoice) => {
    if (!term) return true;
    return [
      invoice.code,
      invoice.customerName,
      invoice.customerEmail,
      invoice.unitCode,
      invoice.contractCode,
    ]
      .map((value) => String(value ?? "").toLowerCase())
      .some((value) => value.includes(term));
  });
  const unpaidTotal = list
    .filter((invoice) => invoice.status === "Unpaid")
    .reduce((sum, invoice) => sum + Number(invoice.amount), 0);

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Hoá đơn cơ sở</h1>
        <p className="mt-1.5 text-small text-muted">
          Hoá đơn của {invoices.facility ? `cơ sở ${invoices.facility.name}` : "cơ sở"} — 500 hoá
          đơn mới nhất. Khách thanh toán trên ứng dụng; hoá đơn đã thu qua cổng có thể ghi nhận hoàn
          tiền sau khi bạn chuyển khoản hoàn cho khách.
        </p>
      </div>

      <div className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,0.8fr)] items-end gap-3 max-[900px]:grid-cols-2 max-[760px]:grid-cols-1">
        <label className="grid gap-1.5">
          <span className="text-label text-muted">Tìm kiếm</span>
          <div className={INPUT_WRAP}>
            <Search size={15} />
            <input
              className={INPUT_ELEMENT}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Mã hoá đơn, khách, khoang, hợp đồng…"
            />
          </div>
        </label>
        <FilterSelect label="Trạng thái" value={status} onChange={setStatus}>
          <option value="">Tất cả trạng thái</option>
          {Object.keys(INVOICE_STATUS_LABEL).map((value) => (
            <option key={value} value={value}>
              {INVOICE_STATUS_LABEL[value]}
            </option>
          ))}
        </FilterSelect>
        <FilterSelect label="Loại hoá đơn" value={type} onChange={setType}>
          <option value="">Tất cả loại</option>
          {Object.keys(INVOICE_TYPE_LABEL).map((value) => (
            <option key={value} value={value}>
              {INVOICE_TYPE_LABEL[value]}
            </option>
          ))}
        </FilterSelect>
      </div>

      {invoices.error ? (
        <SurfaceState
          {...loadErrorState(invoices.error, "hoá đơn cơ sở")}
          action={{ label: "Thử lại", onClick: invoices.reload }}
        />
      ) : !invoices.data ? (
        <SurfaceState variant="loading" title="Đang tải hoá đơn…" />
      ) : list.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Không có hoá đơn phù hợp"
          description="Thử đổi trạng thái, loại hoặc từ khoá tìm kiếm."
        />
      ) : (
        <div className="overflow-x-auto" aria-busy={invoices.loading}>
          <div className={`min-w-[920px] ${invoices.loading ? "opacity-60" : ""}`}>
            <div
              className={`grid ${TABLE_COLUMNS} gap-4 bg-[var(--dark)] px-3.5 py-2.5 text-label text-[rgba(233,234,230,.62)]`}
            >
              <span>Hoá đơn</span>
              <span>Loại</span>
              <span>Khách hàng</span>
              <span className="text-right">Số tiền</span>
              <span>Trạng thái</span>
              <span>Hạn / thanh toán</span>
            </div>
            <div className="mt-1.5 grid gap-1.5">
              {list.map((invoice) => (
                <div
                  key={invoice.id}
                  className={`grid ${TABLE_COLUMNS} items-center gap-4 bg-surface px-3.5 py-3`}
                >
                  <div className="min-w-0">
                    <strong className="block truncate text-small font-semibold text-ink">
                      {invoice.code}
                    </strong>
                    <span className="mt-0.5 block truncate text-label text-muted">
                      {invoice.title ?? "—"}
                    </span>
                  </div>
                  <span className="text-label text-ink">
                    {labelOf(INVOICE_TYPE_LABEL, invoice.type)}
                  </span>
                  <div className="min-w-0">
                    <span className="block truncate text-small text-ink">
                      {invoice.customerName ?? invoice.customerEmail ?? "—"}
                    </span>
                    <span className="mt-0.5 block truncate text-label text-muted">
                      {[
                        invoice.unitCode && `Khoang ${invoice.unitCode}`,
                        invoice.contractCode ??
                          (invoice.orderId ? `Đơn #${invoice.orderId}` : null),
                      ]
                        .filter(Boolean)
                        .join(" · ") || "—"}
                    </span>
                  </div>
                  <span className="text-right text-small font-semibold text-ink tabular-nums">
                    {formatMoney(invoice.amount)}
                  </span>
                  <StatusTag tone={toneOf(INVOICE_STATUS_TONE, invoice.status)}>
                    {labelOf(INVOICE_STATUS_LABEL, invoice.status)}
                  </StatusTag>
                  <span className="text-label text-ink tabular-nums">
                    {invoice.paidAt
                      ? `Đã trả ${formatDateTime(invoice.paidAt)}`
                      : `Hạn ${formatDateTime(invoice.dueDate)}`}
                    <span className="mt-0.5 block text-muted">
                      Tạo {formatDate(invoice.createdAt)}
                    </span>
                    {invoice.paidAt && (
                      <button
                        type="button"
                        className="mt-1 block text-label text-ink underline hover:text-brand"
                        onClick={() => setRefundFor({ id: invoice.id, code: invoice.code })}
                      >
                        Hoàn tiền
                      </button>
                    )}
                  </span>
                </div>
              ))}
            </div>
            <div className="mt-3 flex flex-wrap justify-between gap-3 text-label text-muted">
              <span>{list.length} hoá đơn</span>
              <span>Chưa thanh toán trong danh sách: {formatMoney(unpaidTotal)}</span>
            </div>
          </div>
        </div>
      )}

      {refundFor && (
        <RefundPanel
          key={refundFor.id}
          invoiceId={refundFor.id}
          code={refundFor.code}
          onClose={() => setRefundFor(null)}
        />
      )}
    </div>
  );
}

/** FM ghi nhận đã chuyển khoản hoàn tiền cho khách (payOS không có API hoàn tiền). */
function RefundPanel({
  invoiceId,
  code,
  onClose,
}: {
  invoiceId: number;
  code: string;
  onClose: () => void;
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
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    } finally {
      setBusy(false);
    }
  }

  return (
    <DetailPanel
      title={`Hoàn tiền ${code}`}
      eyebrow="Ghi nhận hoàn tiền thủ công"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>
            Đóng
          </Button>
          <Button pending={busy} onClick={submit} disabled={!summary || summary.refundable <= 0}>
            Ghi nhận đã hoàn
          </Button>
        </>
      }
    >
      <div className="grid gap-4">
        <p className="m-0 text-small text-muted">
          Chuyển khoản hoàn cho khách trước, rồi ghi nhận tại đây để đối soát. Không ghi nhận vượt
          số đã thu qua cổng.
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
          </>
        )}
      </div>
    </DetailPanel>
  );
}
