import { CircleDollarSign, UserRound, Wallet } from "lucide-react";
import { useState } from "react";
import { formatPrice } from "../../mocks/catalog";
import { fmInvoices, type InvoiceStatus, type InvoiceType } from "../../mocks/fm";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

type TypeFilter = "all" | InvoiceType;
type StatusFilter = "all" | InvoiceStatus;

const TYPE_LABEL: Record<InvoiceType, string> = {
  Deposit: "Đặt cọc",
  Rental: "Thuê kho",
  Extension: "Gia hạn",
  Penalty: "Phạt",
  Service: "Dịch vụ",
};

const STATUS_LABEL: Record<InvoiceStatus, string> = {
  Unpaid: "Chưa thanh toán",
  Paid: "Đã thanh toán",
  Canceled: "Đã huỷ",
};

const STATUS_TONE: Record<InvoiceStatus, "warning" | "success" | "neutral"> = {
  Unpaid: "warning",
  Paid: "success",
  Canceled: "neutral",
};

export function FacilityInvoicesPage() {
  const [typeFilter, setTypeFilter] = useState<TypeFilter>("all");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [openId, setOpenId] = useState<string | null>(null);

  const list = fmInvoices.filter(
    (invoice) =>
      (typeFilter === "all" || invoice.type === typeFilter) &&
      (statusFilter === "all" || invoice.status === statusFilter),
  );
  const openInvoice = fmInvoices.find((invoice) => invoice.id === openId) ?? null;

  const totalUnpaid = fmInvoices
    .filter((invoice) => invoice.status === "Unpaid")
    .reduce((sum, invoice) => sum + invoice.amount, 0);
  const totalPaid = fmInvoices
    .filter((invoice) => invoice.status === "Paid")
    .reduce((sum, invoice) => sum + invoice.amount, 0);

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Quản lý hoá đơn cơ sở</h1>
        <p className="mt-1.5 text-small text-muted">
          Hoá đơn phát sinh từ khách hàng thuộc cơ sở của bạn — chỉ xem, hệ thống tự tạo hoá đơn
          theo từng flow (đặt cọc, gia hạn, trả kho, sự cố).
        </p>
      </div>

      <div className="grid grid-cols-2 gap-2 max-[520px]:grid-cols-1">
        <div className="bg-surface p-5">
          <span className="flex items-center gap-1.5 text-label text-muted">
            <Wallet size={13} /> Tổng công nợ chưa thu
          </span>
          <strong className="mt-2 block text-stat text-warning">
            {formatPrice(totalUnpaid)} đ
          </strong>
        </div>
        <div className="bg-surface p-5">
          <span className="flex items-center gap-1.5 text-label text-muted">
            <CircleDollarSign size={13} /> Tổng đã thu
          </span>
          <strong className="mt-2 block text-stat text-success">{formatPrice(totalPaid)} đ</strong>
        </div>
      </div>

      <div className="grid grid-cols-2 items-end gap-3 max-[760px]:grid-cols-1">
        <FilterSelect
          label="Loại hoá đơn"
          value={typeFilter}
          onChange={(value) => setTypeFilter(value as TypeFilter)}
        >
          <option value="all">Tất cả loại hoá đơn</option>
          {(Object.keys(TYPE_LABEL) as InvoiceType[]).map((type) => (
            <option key={type} value={type}>
              {TYPE_LABEL[type]}
            </option>
          ))}
        </FilterSelect>
        <FilterSelect
          label="Trạng thái"
          value={statusFilter}
          onChange={(value) => setStatusFilter(value as StatusFilter)}
        >
          <option value="all">Tất cả trạng thái</option>
          {(Object.keys(STATUS_LABEL) as InvoiceStatus[]).map((status) => (
            <option key={status} value={status}>
              {STATUS_LABEL[status]}
            </option>
          ))}
        </FilterSelect>
      </div>

      {list.length === 0 ? (
        <SurfaceState variant="empty" title="Không có hoá đơn nào phù hợp bộ lọc" />
      ) : (
        <div className="grid gap-2">
          {list.map((invoice) => (
            <button
              key={invoice.id}
              className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,0.9fr)_minmax(0,0.7fr)_auto] items-center gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle max-[900px]:grid-cols-1 max-[900px]:gap-2"
              onClick={() => setOpenId(invoice.id)}
            >
              <div>
                <span className="text-label text-muted">
                  {TYPE_LABEL[invoice.type]} · {invoice.code}
                </span>
                <strong className="mt-1 block text-small font-semibold text-ink">
                  {invoice.customerName} · Khoang {invoice.unitCode}
                </strong>
              </div>
              <span className="flex items-center gap-1.5 text-label text-muted">
                <UserRound size={13} /> Hạn {invoice.dueDate}
              </span>
              <strong className="text-[18px] font-semibold text-ink">
                {formatPrice(invoice.amount)} đ
              </strong>
              <StatusTag tone={STATUS_TONE[invoice.status]}>
                {STATUS_LABEL[invoice.status]}
              </StatusTag>
            </button>
          ))}
        </div>
      )}

      {openInvoice && (
        <DetailPanel
          title={openInvoice.code}
          eyebrow={TYPE_LABEL[openInvoice.type]}
          onClose={() => setOpenId(null)}
        >
          <div className="grid gap-2 text-small text-muted">
            <span className="flex items-center gap-2">
              <UserRound size={14} /> {openInvoice.customerName}
            </span>
            <span>Khoang: {openInvoice.unitCode}</span>
            <span>Hạn thanh toán: {openInvoice.dueDate}</span>
          </div>
          <div className="mt-4 bg-surface-subtle p-3.5">
            <div className="flex items-center justify-between gap-4 text-small">
              <span className="text-muted">Số tiền</span>
              <strong className="text-[18px] font-semibold text-ink">
                {formatPrice(openInvoice.amount)} đ
              </strong>
            </div>
            <div className="mt-2 flex items-center justify-between gap-4 text-small">
              <span className="text-muted">Trạng thái</span>
              <StatusTag tone={STATUS_TONE[openInvoice.status]}>
                {STATUS_LABEL[openInvoice.status]}
              </StatusTag>
            </div>
          </div>
        </DetailPanel>
      )}
    </div>
  );
}
