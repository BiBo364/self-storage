import { ArrowRight, CalendarClock } from "lucide-react";
import { useState } from "react";
import type { Navigate } from "../app/types";
import { facilities, formatPrice } from "../mocks/catalog";
import {
  INVOICE_TYPE_LABEL,
  type Invoice,
  type InvoiceStatus,
  type InvoiceType,
  invoices,
  isInvoicePaid,
  markInvoicePaid,
} from "../mocks/invoices";
import { Button } from "../components/ui/Button";
import { DemoNotice } from "../components/ui/DemoNotice";
import { DetailPanel } from "../components/ui/DetailPanel";
import { FilterSelect } from "../components/ui/FilterSelect";
import { StatusTag } from "../components/ui/StatusTag";
import { SurfaceState } from "../components/ui/SurfaceState";

const STATUS_META: Record<
  InvoiceStatus,
  { label: string; tone: "warning" | "success" | "neutral" }
> = {
  Unpaid: { label: "Chưa thanh toán", tone: "warning" },
  Paid: { label: "Đã thanh toán", tone: "success" },
  Canceled: { label: "Đã huỷ", tone: "neutral" },
};

function effectiveStatus(invoice: Invoice): InvoiceStatus {
  if (invoice.status === "Canceled") return "Canceled";
  return isInvoicePaid(invoice.id) ? "Paid" : "Unpaid";
}

export function InvoicesPage({ navigate }: { navigate: Navigate }) {
  const [typeFilter, setTypeFilter] = useState<"all" | InvoiceType>("all");
  const [statusFilter, setStatusFilter] = useState<"all" | InvoiceStatus>("all");
  const [openId, setOpenId] = useState<string | null>(null);
  const [payingId, setPayingId] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<{ id: string; ok: boolean } | null>(null);
  const [, refresh] = useState(0);

  const list = invoices.filter((invoice) => {
    if (typeFilter !== "all" && invoice.type !== typeFilter) return false;
    if (statusFilter !== "all" && effectiveStatus(invoice) !== statusFilter) return false;
    return true;
  });

  const openInvoice = invoices.find((invoice) => invoice.id === openId) ?? null;

  function pay(invoice: Invoice) {
    setPayingId(invoice.id);
    setOutcome(null);
    window.setTimeout(() => {
      setPayingId(null);
      if (invoice.simulateFailure) {
        setOutcome({ id: invoice.id, ok: false });
        return;
      }
      markInvoicePaid(invoice.id);
      refresh((n) => n + 1);
      setOutcome({ id: invoice.id, ok: true });
    }, 900);
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Hoá đơn &amp; thanh toán</h1>
        <p className="mt-1.5 text-small text-muted">
          Theo dõi và thanh toán mọi khoản phát sinh trong suốt vòng đời thuê kho.
        </p>
      </div>

      <div className="grid grid-cols-2 items-end gap-3 max-[760px]:grid-cols-1">
        <FilterSelect
          label="Loại hoá đơn"
          value={typeFilter}
          onChange={(value) => setTypeFilter(value as "all" | InvoiceType)}
        >
          <option value="all">Tất cả loại hoá đơn</option>
          {Object.entries(INVOICE_TYPE_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </FilterSelect>
        <FilterSelect
          label="Trạng thái"
          value={statusFilter}
          onChange={(value) => setStatusFilter(value as "all" | InvoiceStatus)}
        >
          <option value="all">Tất cả trạng thái</option>
          <option value="Unpaid">Chưa thanh toán</option>
          <option value="Paid">Đã thanh toán</option>
          <option value="Canceled">Đã huỷ</option>
        </FilterSelect>
      </div>

      {list.length === 0 ? (
        <SurfaceState variant="empty" title="Không có hoá đơn phù hợp bộ lọc" />
      ) : (
        <div className="grid gap-2">
          {list.map((invoice) => {
            const status = effectiveStatus(invoice);
            const meta = STATUS_META[status];
            return (
              <button
                key={invoice.id}
                className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,0.7fr)_auto] items-center gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle max-[760px]:grid-cols-1 max-[760px]:gap-2"
                onClick={() => {
                  setOpenId(invoice.id);
                  setOutcome(null);
                }}
              >
                <div>
                  <span className="text-label text-muted">
                    {invoice.code} · {INVOICE_TYPE_LABEL[invoice.type]}
                  </span>
                  <h2 className="mt-1 text-section text-ink">{invoice.title}</h2>
                </div>
                <span className="flex items-center gap-1.5 text-label text-muted">
                  <CalendarClock size={14} /> Hạn {invoice.dueDate}
                </span>
                <strong className="text-[18px] font-semibold text-ink">
                  {formatPrice(invoice.amount)} đ
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
          eyebrow={`${openInvoice.code} · ${INVOICE_TYPE_LABEL[openInvoice.type]}`}
          onClose={() => setOpenId(null)}
        >
          <p className="text-small leading-relaxed text-muted">{openInvoice.description}</p>
          <dl className="mt-5 grid gap-2.5">
            <DetailRow label="Khoang liên quan" value={openInvoice.unitCode} />
            <DetailRow
              label="Cơ sở"
              value={
                facilities.find((facility) => facility.id === openInvoice.facilityId)?.name ?? "—"
              }
            />
            <DetailRow label="Số tiền" value={`${formatPrice(openInvoice.amount)} đ`} />
            {openInvoice.discountAmount > 0 && (
              <DetailRow
                label="Giảm giá"
                value={`-${formatPrice(openInvoice.discountAmount)} đ`}
                tone="success"
              />
            )}
            <DetailRow label="Hạn thanh toán" value={openInvoice.dueDate} />
          </dl>

          {outcome?.id === openInvoice.id && outcome.ok && (
            <div className="mt-5">
              <DemoNotice tone="success">Thanh toán thành công.</DemoNotice>
              {openInvoice.type === "Deposit" && (
                <Button
                  className="mt-3"
                  onClick={() => navigate(`/appointments/new?orderId=${openInvoice.orderId}`)}
                >
                  Đặt lịch check-in <ArrowRight size={16} />
                </Button>
              )}
            </div>
          )}

          {outcome?.id === openInvoice.id && !outcome.ok && (
            <div className="mt-5">
              <DemoNotice tone="error">
                Khoang {openInvoice.unitCode} vừa có người khác đặt cọc trước. Đội ngũ vận hành sẽ
                gửi đề xuất khoang khác cho bạn.
              </DemoNotice>
            </div>
          )}

          {effectiveStatus(openInvoice) === "Unpaid" && !outcome && (
            <Button
              className="mt-6 w-full"
              pending={payingId === openInvoice.id}
              onClick={() => pay(openInvoice)}
            >
              {payingId === openInvoice.id
                ? "Đang chuyển đến cổng thanh toán…"
                : "Tiến hành thanh toán"}
            </Button>
          )}
        </DetailPanel>
      )}
    </div>
  );
}

function DetailRow({
  label,
  value,
  tone = "default",
}: {
  label: string;
  value: string;
  tone?: "default" | "success";
}) {
  return (
    <div className="flex items-center justify-between gap-4 text-small">
      <dt className="text-muted">{label}</dt>
      <dd className={`m-0 font-semibold ${tone === "success" ? "text-success" : "text-ink"}`}>
        {value}
      </dd>
    </div>
  );
}
