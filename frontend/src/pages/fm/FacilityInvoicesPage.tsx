import { CircleDollarSign, Clock3, UserRound, Wallet } from "lucide-react";
import { useEffect, useState } from "react";
import { ApiError } from "../../app/api";
import {
  loadFacilityInvoices,
  type FmInvoice,
  type FmInvoicePage,
  type FmInvoiceStatus,
  type FmInvoiceType,
  type FmPaymentStatus,
} from "../../app/fmInvoices";
import { Button } from "../../components/ui/Button";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";
import { formatPrice } from "../../mocks/catalog";
import { FmRefundQueuePanel } from "./FmRefundQueuePanel";
import { loadFmRefundCases } from "../../app/fmInvoices";

type TypeFilter = "all" | FmInvoiceType;
type StatusFilter = "all" | FmInvoiceStatus;
type PaymentFilter = "all" | "needs-reconciliation" | "duplicate";

const PAGE_SIZE = 20;

const TYPE_LABEL: Record<FmInvoiceType, string> = {
  Deposit: "Đặt cọc",
  DepositSupplement: "Cọc bổ sung",
  Rental: "Thuê kho",
  Extension: "Gia hạn",
  Penalty: "Phạt",
  Service: "Dịch vụ",
};

const STATUS_LABEL: Record<FmInvoiceStatus, string> = {
  Unpaid: "Chưa thanh toán",
  Paid: "Đã thanh toán",
  Canceled: "Đã huỷ",
  Expired: "Quá hạn",
};

const STATUS_TONE: Record<FmInvoiceStatus, "warning" | "success" | "neutral" | "danger"> = {
  Unpaid: "warning",
  Paid: "success",
  Canceled: "neutral",
  Expired: "danger",
};

const PAYMENT_LABEL: Record<FmPaymentStatus, string> = {
  Pending: "Chưa có kết quả từ cổng",
  Success: "Cổng xác nhận",
  Failed: "Cổng báo thất bại",
  Duplicate: "Thanh toán trùng",
};

function formatDate(value: string | null) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("vi-VN", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(value));
}

function paymentTone(invoice: FmInvoice): "neutral" | "warning" | "success" | "danger" {
  if (invoice.duplicateRefundRequired || invoice.needsReconciliation) return "danger";
  if (invoice.paymentStatus === "Pending") return "warning";
  if (invoice.paymentStatus === "Success") return "success";
  return "neutral";
}

export function FacilityInvoicesPage() {
  const [tab, setTab] = useState<"invoices" | "refunds">("invoices");
  const [typeFilter, setTypeFilter] = useState<TypeFilter>("all");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [paymentFilter, setPaymentFilter] = useState<PaymentFilter>("all");
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [data, setData] = useState<FmInvoicePage>({
    items: [],
    page: 0,
    size: PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
  });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    if (tab !== "invoices") return;
    const controller = new AbortController();
    const paymentStatus =
      paymentFilter === "needs-reconciliation"
        ? ("Pending" as const)
        : paymentFilter === "duplicate"
          ? ("Duplicate" as const)
          : undefined;
    loadFacilityInvoices(
      {
        status: statusFilter === "all" ? undefined : statusFilter,
        type: typeFilter === "all" ? undefined : typeFilter,
        paymentStatus,
        olderThanMinutes: paymentFilter === "needs-reconciliation" ? 15 : undefined,
        page,
        size: PAGE_SIZE,
      },
      controller.signal,
    )
      .then((result) => {
        if (!controller.signal.aborted) {
          setData(result);
          setOpenId(null);
        }
      })
      .catch((reason: unknown) => {
        if (controller.signal.aborted) return;
        setError(
          reason instanceof ApiError || reason instanceof Error
            ? reason.message
            : "Không tải được danh sách hoá đơn.",
        );
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [typeFilter, statusFilter, paymentFilter, page, reloadKey, tab]);

  function reload() {
    setLoading(true);
    setError(null);
    setReloadKey((key) => key + 1);
  }

  function changeType(value: TypeFilter) {
    setTypeFilter(value);
    setPage(0);
    setLoading(true);
    setError(null);
  }

  function changeStatus(value: StatusFilter) {
    setStatusFilter(value);
    setPage(0);
    setLoading(true);
    setError(null);
  }

  function changePayment(value: PaymentFilter) {
    setPaymentFilter(value);
    setPage(0);
    setLoading(true);
    setError(null);
  }

  function changePage(next: number) {
    setPage(next);
    setLoading(true);
    setError(null);
  }

  function changeTab(next: "invoices" | "refunds") {
    setTab(next);
    setOpenId(null);
    if (next === "invoices") {
      setLoading(true);
      setError(null);
    }
  }

  const invoices = data.items;
  const openInvoice = invoices.find((invoice) => invoice.id === openId) ?? null;
  const totalUnpaid = invoices
    .filter((invoice) => invoice.status === "Unpaid")
    .reduce((sum, invoice) => sum + invoice.amount, 0);
  const totalPaid = invoices
    .filter((invoice) => invoice.status === "Paid")
    .reduce((sum, invoice) => sum + invoice.amount, 0);

  const header = (
    <div>
      <h1 className="m-0 text-title text-ink">Quản lý hoá đơn cơ sở</h1>
      <p className="mt-1.5 text-small text-muted">
        Hoá đơn phát sinh từ khách hàng thuộc cơ sở của bạn — chỉ xem, hệ thống tự tạo hoá đơn theo
        từng flow (đặt cọc, gia hạn, trả kho, sự cố).
      </p>
    </div>
  );

  const tabs = (
    <div className="flex gap-2 border-b border-rule" role="tablist" aria-label="Hoá đơn cơ sở">
      <button
        type="button"
        role="tab"
        aria-selected={tab === "invoices"}
        className={`border-b-2 px-3 py-2 text-small font-semibold ${tab === "invoices" ? "border-accent text-accent" : "border-transparent text-muted hover:text-ink"}`}
        onClick={() => changeTab("invoices")}
      >
        Hoá đơn
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={tab === "refunds"}
        className={`border-b-2 px-3 py-2 text-small font-semibold ${tab === "refunds" ? "border-accent text-accent" : "border-transparent text-muted hover:text-ink"}`}
        onClick={() => changeTab("refunds")}
      >
        Hoàn tiền
      </button>
    </div>
  );

  if (tab === "refunds") {
    return (
      <div className="grid gap-6">
        {header}
        {tabs}
        <FmRefundQueuePanel loadCases={loadFmRefundCases} />
      </div>
    );
  }

  return (
    <div className="grid gap-6">
      {header}
      {tabs}

      <div className="grid grid-cols-2 gap-2 max-[520px]:grid-cols-1">
        <div className="bg-surface p-5">
          <span className="flex items-center gap-1.5 text-label text-muted">
            <Wallet size={13} /> Công nợ chưa thu trong trang
          </span>
          <strong className="mt-2 block text-stat text-warning">
            {formatPrice(totalUnpaid)} đ
          </strong>
        </div>
        <div className="bg-surface p-5">
          <span className="flex items-center gap-1.5 text-label text-muted">
            <CircleDollarSign size={13} /> Đã thu trong trang
          </span>
          <strong className="mt-2 block text-stat text-success">{formatPrice(totalPaid)} đ</strong>
        </div>
      </div>

      <div className="grid grid-cols-3 items-end gap-3 max-[900px]:grid-cols-1">
        <FilterSelect
          label="Loại hoá đơn"
          value={typeFilter}
          onChange={(value) => changeType(value as TypeFilter)}
        >
          <option value="all">Tất cả loại hoá đơn</option>
          {(Object.keys(TYPE_LABEL) as FmInvoiceType[]).map((type) => (
            <option key={type} value={type}>
              {TYPE_LABEL[type]}
            </option>
          ))}
        </FilterSelect>
        <FilterSelect
          label="Trạng thái"
          value={statusFilter}
          onChange={(value) => changeStatus(value as StatusFilter)}
        >
          <option value="all">Tất cả trạng thái</option>
          {(Object.keys(STATUS_LABEL) as FmInvoiceStatus[]).map((status) => (
            <option key={status} value={status}>
              {STATUS_LABEL[status]}
            </option>
          ))}
        </FilterSelect>
        <FilterSelect
          label="Đối soát"
          value={paymentFilter}
          onChange={(value) => changePayment(value as PaymentFilter)}
        >
          <option value="all">Tất cả giao dịch</option>
          <option value="needs-reconciliation">Pending quá 15 phút</option>
          <option value="duplicate">Thanh toán trùng</option>
        </FilterSelect>
      </div>

      {loading ? (
        <SurfaceState variant="loading" title="Đang tải hoá đơn cơ sở" />
      ) : error ? (
        <SurfaceState
          variant="error"
          title="Không tải được hoá đơn"
          description={error}
          action={{ label: "Thử lại", onClick: reload }}
        />
      ) : invoices.length === 0 ? (
        <SurfaceState variant="empty" title="Không có hoá đơn nào phù hợp bộ lọc" />
      ) : (
        <div className="grid gap-2">
          {invoices.map((invoice) => (
            <button
              key={invoice.id}
              className={`grid grid-cols-[minmax(0,1.4fr)_minmax(0,0.9fr)_minmax(0,0.7fr)_auto] items-center gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle max-[900px]:grid-cols-1 max-[900px]:gap-2 ${
                invoice.needsReconciliation || invoice.duplicateRefundRequired
                  ? "border-l-4 border-danger"
                  : ""
              }`}
              onClick={() => setOpenId(invoice.id)}
            >
              <div>
                <span className="flex flex-wrap items-center gap-2 text-label text-muted">
                  {TYPE_LABEL[invoice.type]} · {invoice.code}
                  {invoice.needsReconciliation && <StatusTag tone="danger">Cần đối soát</StatusTag>}
                  {invoice.duplicateRefundRequired && (
                    <StatusTag tone="danger">Cần hoàn tiền</StatusTag>
                  )}
                </span>
                <strong className="mt-1 block text-small font-semibold text-ink">
                  {invoice.customerName ?? invoice.customerEmail} · Khoang {invoice.unitCode ?? "—"}
                </strong>
                {invoice.paymentStatus && (
                  <span className="mt-1 flex items-center gap-1.5 text-label text-muted">
                    <Clock3 size={12} />
                    {PAYMENT_LABEL[invoice.paymentStatus]}
                    {invoice.paymentStatus === "Pending" && ` · ${invoice.pendingAgeMinutes} phút`}
                  </span>
                )}
              </div>
              <span className="flex items-center gap-1.5 text-label text-muted">
                <UserRound size={13} /> Hạn {formatDate(invoice.dueDate)}
              </span>
              <strong className="text-[18px] font-semibold text-ink">
                {formatPrice(invoice.amount)} đ
              </strong>
              <div className="grid justify-items-end gap-1">
                <StatusTag tone={STATUS_TONE[invoice.status]}>
                  {STATUS_LABEL[invoice.status]}
                </StatusTag>
                {invoice.paymentStatus && (
                  <StatusTag tone={paymentTone(invoice)}>
                    {PAYMENT_LABEL[invoice.paymentStatus]}
                  </StatusTag>
                )}
              </div>
            </button>
          ))}
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <span className="text-label text-muted">
          Trang {Math.min(page + 1, Math.max(1, data.totalPages))} / {Math.max(1, data.totalPages)}{" "}
          · {data.totalElements} hoá đơn
        </span>
        <div className="flex gap-2">
          <Button
            variant="secondary"
            disabled={loading || page === 0}
            onClick={() => changePage(page - 1)}
          >
            Trang trước
          </Button>
          <Button
            variant="secondary"
            disabled={loading || page + 1 >= data.totalPages}
            onClick={() => changePage(page + 1)}
          >
            Trang sau
          </Button>
        </div>
      </div>

      {openInvoice && (
        <DetailPanel
          title={openInvoice.code}
          eyebrow={TYPE_LABEL[openInvoice.type]}
          onClose={() => setOpenId(null)}
        >
          <div className="grid gap-2 text-small text-muted">
            <span className="flex items-center gap-2">
              <UserRound size={14} /> {openInvoice.customerName ?? openInvoice.customerEmail}
            </span>
            <span>Khoang: {openInvoice.unitCode ?? "—"}</span>
            <span>Hạn thanh toán: {formatDate(openInvoice.dueDate)}</span>
            <span>
              Giao dịch gần nhất:{" "}
              {openInvoice.paymentStatus
                ? PAYMENT_LABEL[openInvoice.paymentStatus]
                : "Chưa có giao dịch"}
            </span>
            {openInvoice.vnpTxnRef && <span>Mã giao dịch: {openInvoice.vnpTxnRef}</span>}
            {openInvoice.paymentStatus === "Pending" && (
              <span>Đã chờ: {openInvoice.pendingAgeMinutes} phút</span>
            )}
            {openInvoice.needsReconciliation && (
              <span className="text-danger">
                Pending quá 15 phút — cần tra portal VNPay và đối soát thủ công.
              </span>
            )}
            {openInvoice.duplicateRefundRequired && (
              <span className="text-danger">Giao dịch trùng — cần hoàn tiền thủ công.</span>
            )}
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
