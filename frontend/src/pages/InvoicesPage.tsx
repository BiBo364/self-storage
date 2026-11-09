import { CalendarClock } from "lucide-react";
import { useState } from "react";
import type { Navigate } from "../app/types";
import { formatDate, formatDateTime, formatMoney } from "../app/format";
import {
  fetchInvoices,
  INVOICE_STATUS,
  INVOICE_TYPE_LABEL,
  useInvoicePayment,
  useResource,
} from "../app/services/customer";
import { Button } from "../components/ui/Button";
import { DemoNotice } from "../components/ui/DemoNotice";
import { DetailPanel } from "../components/ui/DetailPanel";
import { FilterSelect } from "../components/ui/FilterSelect";
import { StatusTag } from "../components/ui/StatusTag";
import { SurfaceState } from "../components/ui/SurfaceState";

export function InvoicesPage({ navigate }: { navigate: Navigate }) {
  const invoices = useResource(fetchInvoices);
  const payment = useInvoicePayment(invoices.reload);
  const [typeFilter, setTypeFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");
  const [openId, setOpenId] = useState<number | null>(null);

  const all = invoices.data ?? [];
  const list = all.filter((invoice) => {
    if (typeFilter !== "all" && invoice.type !== typeFilter) return false;
    if (statusFilter !== "all" && invoice.status !== statusFilter) return false;
    return true;
  });
  const openInvoice = all.find((invoice) => invoice.id === openId) ?? null;
  const unpaidTotal = all
    .filter((invoice) => invoice.status === "Unpaid")
    .reduce((sum, invoice) => sum + Number(invoice.amount), 0);

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Hoá đơn &amp; thanh toán</h1>
          <p className="mt-1.5 text-small text-muted">
            Theo dõi và thanh toán mọi khoản phát sinh trong suốt vòng đời thuê kho.
          </p>
        </div>
        {invoices.data && (
          <div className="text-right">
            <span className="text-label text-muted">Chưa thanh toán</span>
            <strong className="block text-stat text-ink">{formatMoney(unpaidTotal)}</strong>
          </div>
        )}
      </div>

      <div className="grid grid-cols-2 items-end gap-3 max-[760px]:grid-cols-1">
        <FilterSelect label="Loại hoá đơn" value={typeFilter} onChange={setTypeFilter}>
          <option value="all">Tất cả loại hoá đơn</option>
          {Object.entries(INVOICE_TYPE_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </FilterSelect>
        <FilterSelect label="Trạng thái" value={statusFilter} onChange={setStatusFilter}>
          <option value="all">Tất cả trạng thái</option>
          {Object.entries(INVOICE_STATUS).map(([value, meta]) => (
            <option key={value} value={value}>
              {meta.label}
            </option>
          ))}
        </FilterSelect>
      </div>

      {payment.payError && openId !== payment.payError.invoiceId && (
        <DemoNotice tone="error">{payment.payError.message}</DemoNotice>
      )}

      {invoices.loading && !invoices.data ? (
        <div className="grid gap-2" role="status" aria-label="Đang tải">
          {[0, 1, 2].map((index) => (
            <div key={index} className="h-[76px] animate-pulse bg-surface" />
          ))}
        </div>
      ) : invoices.error && !invoices.data ? (
        <SurfaceState
          variant="error"
          title="Không tải được hoá đơn"
          description={invoices.error}
          action={{ label: "Thử lại", onClick: invoices.reload }}
        />
      ) : all.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Chưa có hoá đơn nào"
          description="Hoá đơn đặt cọc sẽ xuất hiện sau khi bạn đồng ý một đề xuất khoang."
          action={{ label: "Về Kho của tôi", onClick: () => navigate("/my-storage") }}
        />
      ) : list.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Không có hoá đơn phù hợp bộ lọc"
          action={{
            label: "Xoá bộ lọc",
            onClick: () => {
              setTypeFilter("all");
              setStatusFilter("all");
            },
          }}
        />
      ) : (
        <div className="grid gap-2">
          {list.map((invoice) => {
            const meta = INVOICE_STATUS[invoice.status] ?? {
              label: invoice.status,
              tone: "neutral" as const,
            };
            return (
              <button
                key={invoice.id}
                className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,0.7fr)_auto] items-center gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle max-[760px]:grid-cols-1 max-[760px]:gap-2"
                onClick={() => {
                  setOpenId(invoice.id);
                  payment.clearPayError();
                }}
              >
                <div className="min-w-0">
                  <span className="text-label text-muted">
                    {invoice.code} · {INVOICE_TYPE_LABEL[invoice.type] ?? invoice.type}
                  </span>
                  <h2 className="mt-1 text-section text-ink">{invoice.title}</h2>
                </div>
                <span className="flex items-center gap-1.5 text-label text-muted">
                  <CalendarClock size={14} /> Hạn {formatDate(invoice.dueDate)}
                </span>
                <strong className="text-[18px] font-semibold text-ink">
                  {formatMoney(invoice.amount)}
                </strong>
                <StatusTag tone={meta.tone}>{meta.label}</StatusTag>
              </button>
            );
          })}
        </div>
      )}

      {openInvoice && (
        <DetailPanel
          title={openInvoice.title}
          eyebrow={`${openInvoice.code} · ${INVOICE_TYPE_LABEL[openInvoice.type] ?? openInvoice.type}`}
          onClose={() => setOpenId(null)}
        >
          {openInvoice.description && (
            <p className="text-small leading-relaxed text-muted">{openInvoice.description}</p>
          )}
          <dl className="mt-5 grid gap-2.5">
            <DetailRow
              label="Trạng thái"
              value={INVOICE_STATUS[openInvoice.status]?.label ?? openInvoice.status}
            />
            {openInvoice.orderId !== null && (
              <DetailRow label="Đơn thuê" value={`#${openInvoice.orderId}`} />
            )}
            <DetailRow label="Khoang liên quan" value={openInvoice.unitCode ?? "—"} />
            <DetailRow label="Cơ sở" value={openInvoice.facilityName ?? "—"} />
            <DetailRow label="Số tiền" value={formatMoney(openInvoice.amount)} />
            <DetailRow label="Ngày lập" value={formatDateTime(openInvoice.createdAt)} />
            <DetailRow label="Hạn thanh toán" value={formatDateTime(openInvoice.dueDate)} />
          </dl>

          {payment.payError?.invoiceId === openInvoice.id && (
            <DemoNotice tone="error">{payment.payError.message}</DemoNotice>
          )}

          {openInvoice.status === "Unpaid" && (
            <Button
              className="mt-6 w-full"
              pending={payment.payingId === openInvoice.id}
              onClick={() => payment.pay(openInvoice.id)}
            >
              {payment.payingId === openInvoice.id
                ? "Đang chuyển đến cổng thanh toán…"
                : "Thanh toán"}
            </Button>
          )}
        </DetailPanel>
      )}
    </div>
  );
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-4 text-small">
      <dt className="text-muted">{label}</dt>
      <dd className="m-0 text-right font-semibold text-ink">{value}</dd>
    </div>
  );
}
