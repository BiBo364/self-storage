import { ReceiptText } from "lucide-react";
import { useEffect, useState } from "react";
import { ApiError } from "../../app/api";
import { type RefundCase, type RefundCasePage } from "../../app/adminReconciliation";
import { Button } from "../../components/ui/Button";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";
import { formatPrice } from "../../mocks/catalog";
import { FmRefundRecorder } from "./FmRefundRecorder";

const EMPTY_PAGE: RefundCasePage = {
  items: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
};

function formatDateTime(value: string | null) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("vi-VN", { dateStyle: "short", timeStyle: "short" }).format(
    new Date(value),
  );
}

const REFUND_REASON_LABEL: Record<string, string> = {
  UNIT_UNAVAILABLE: "Khoang không còn khả dụng",
  INVOICE_CLOSED: "Tiền vào sau khi hoá đơn đã đóng",
  UNIT_CHANGE_EXCESS: "Cọc dư khi đổi khoang",
  DEPOSIT_UNSERVED: "Đơn hết hạn khi đã trả cọc",
  HANDOVER_CANCELED: "Đã trả tháng đầu nhưng bàn giao bị huỷ",
  CHECKOUT_DEPOSIT: "Hoàn cọc khi trả kho",
};

function refundReasonLabel(reason: string | null) {
  if (!reason) return "Không rõ";
  return REFUND_REASON_LABEL[reason] ?? reason;
}

/** Hàng đợi hoàn tiền của cơ sở (`Required` / `Processing`); FM ghi nhận khoản đã chuyển cho khách. */
type FmRefundQueuePanelProps = {
  loadCases: (page: number, size?: number) => Promise<RefundCasePage>;
};

export function FmRefundQueuePanel({ loadCases }: FmRefundQueuePanelProps) {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<RefundCasePage>(EMPTY_PAGE);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [selected, setSelected] = useState<RefundCase | null>(null);

  useEffect(() => {
    let active = true;
    loadCases(page)
      .then((result) => {
        if (active) setData(result);
      })
      .catch((reason: unknown) => {
        if (!active) return;
        setError(
          reason instanceof ApiError || reason instanceof Error
            ? reason.message
            : "Không tải được danh sách khoản cần hoàn tiền.",
        );
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [page, reloadKey, loadCases]);

  function reload() {
    setLoading(true);
    setError(null);
    setReloadKey((key) => key + 1);
  }

  return (
    <section className="grid gap-3">
      <div>
        <h2 className="m-0 text-section text-ink">Chờ hoàn tiền</h2>
        <p className="mt-1.5 text-small text-muted">
          Tiền đã vào nhưng phải trả lại khách. Chuyển khoản hoàn rồi ghi nhận tại đây; hoàn đủ số
          phải hoàn thì khoản rời danh sách.
        </p>
      </div>

      {loading ? (
        <SurfaceState variant="loading" title="Đang tải khoản chờ hoàn tiền" />
      ) : error ? (
        <SurfaceState
          variant="error"
          title="Không tải được khoản chờ hoàn tiền"
          description={error}
          action={{ label: "Thử lại", onClick: reload }}
        />
      ) : data.items.length === 0 ? (
        <SurfaceState variant="empty" title="Không có khoản nào chờ hoàn tiền" />
      ) : (
        <div className="grid gap-2">
          {data.items.map((item) => (
            <button
              key={item.invoiceId}
              className="grid grid-cols-[minmax(0,1.5fr)_minmax(0,0.9fr)_auto] items-center gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle max-[760px]:grid-cols-1 max-[760px]:gap-2"
              onClick={() => setSelected(item)}
            >
              <div>
                <span className="flex items-center gap-2 text-label text-muted">
                  <ReceiptText size={14} /> {item.invoiceCode}
                </span>
                <strong className="mt-1 block text-small font-semibold text-ink">
                  Invoice #{item.invoiceId} · {formatPrice(item.amount)} đ
                </strong>
                <span className="mt-1 block text-label text-muted">
                  {refundReasonLabel(item.refundReason)}
                </span>
              </div>
              <div className="text-small text-muted">
                <span className="block text-label">Yêu cầu lúc</span>
                <span className="text-ink">{formatDateTime(item.refundRequestedAt)}</span>
              </div>
              <StatusTag tone="warning">
                {item.refundStatus === "Processing" ? "Đã hoàn một phần" : "Chờ hoàn tiền"}
              </StatusTag>
            </button>
          ))}
        </div>
      )}

      {!loading && !error && data.totalPages > 1 && (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <span className="text-label text-muted">
            Trang {Math.min(page + 1, data.totalPages)} / {data.totalPages} · {data.totalElements}{" "}
            khoản
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
      )}

      {selected && (
        <DetailPanel
          title={selected.invoiceCode}
          eyebrow={`Invoice #${selected.invoiceId} · Chờ hoàn tiền`}
          onClose={() => setSelected(null)}
        >
          <div className="grid gap-4">
            <div className="grid grid-cols-2 gap-3 bg-surface-subtle p-3.5 text-small">
              <span className="text-muted">Số tiền phải hoàn</span>
              <strong className="text-right text-ink">{formatPrice(selected.amount)} đ</strong>
              <span className="text-muted">Đơn thuê</span>
              <strong className="text-right text-ink">
                {selected.orderId ? `#${selected.orderId}` : "—"}
              </strong>
              <span className="text-muted">Lý do</span>
              <strong className="text-right text-ink">
                {refundReasonLabel(selected.refundReason)}
              </strong>
              <span className="text-muted">Yêu cầu lúc</span>
              <strong className="text-right text-ink">
                {formatDateTime(selected.refundRequestedAt)}
              </strong>
              <span className="text-muted">Mã giao dịch</span>
              <strong className="break-all text-right text-ink">{selected.vnpTxnRef ?? "—"}</strong>
              <span className="text-muted">Mã VNPay</span>
              <strong className="break-all text-right text-ink">
                {selected.gatewayTransactionNo ?? "—"}
              </strong>
              <span className="text-muted">Đã thanh toán</span>
              <strong className="text-right text-ink">{formatDateTime(selected.paidAt)}</strong>
            </div>

            <FmRefundRecorder invoiceId={selected.invoiceId} onRecorded={reload} />
          </div>
        </DetailPanel>
      )}
    </section>
  );
}
