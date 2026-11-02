import { ArrowRight, CalendarClock } from "lucide-react";
import { useEffect, useState } from "react";
import { formatDate, formatPrice } from "../app/format";
import { listInvoices } from "../app/invoices";
import type { InvoiceStatus, InvoiceSummary, InvoiceType, Navigate } from "../app/types";
import { Button } from "../components/ui/Button";
import { DemoNotice } from "../components/ui/DemoNotice";
import { DetailPanel } from "../components/ui/DetailPanel";
import { FilterSelect } from "../components/ui/FilterSelect";
import { StatusTag } from "../components/ui/StatusTag";
import { SurfaceState } from "../components/ui/SurfaceState";

const INVOICE_TYPE_LABEL: Record<InvoiceType, string> = {
  Deposit: "Đặt cọc",
  DepositSupplement: "Bổ sung cọc",
  Rental: "Tiền thuê",
  Extension: "Gia hạn",
  Penalty: "Phí phát sinh",
  Service: "Dịch vụ",
};

const STATUS_META: Record<
  InvoiceStatus,
  { label: string; tone: "warning" | "success" | "neutral" | "danger" }
> = {
  Unpaid: { label: "Chưa thanh toán", tone: "warning" },
  Paid: { label: "Đã thanh toán", tone: "success" },
  Canceled: { label: "Đã huỷ", tone: "neutral" },
  Expired: { label: "Đã hết hạn", tone: "danger" },
};

type LoadState = "loading" | "ready" | "error";

export function InvoicesPage({ navigate }: { navigate: Navigate }) {
  const [typeFilter, setTypeFilter] = useState<"all" | InvoiceType>("all");
  const [statusFilter, setStatusFilter] = useState<"all" | InvoiceStatus>("all");
  const [openId, setOpenId] = useState<number | null>(null);
  const [invoices, setInvoices] = useState<InvoiceSummary[]>([]);
  const [loadState, setLoadState] = useState<LoadState>("loading");
  const [loadError, setLoadError] = useState("");
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    let cancelled = false;

    async function load() {
      setLoadState("loading");
      setLoadError("");
      try {
        const data = await listInvoices(controller.signal);
        if (cancelled) return;
        setInvoices(data);
        setLoadState("ready");
      } catch (error) {
        if (cancelled || controller.signal.aborted) return;
        setLoadError(error instanceof Error ? error.message : "Không thể tải danh sách hoá đơn.");
        setLoadState("error");
      }
    }

    void load();
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [reloadKey]);

  const list = invoices.filter((invoice) => {
    if (typeFilter !== "all" && invoice.type !== typeFilter) return false;
    if (statusFilter !== "all" && invoice.status !== statusFilter) return false;
    return true;
  });

  const openInvoice = invoices.find((invoice) => invoice.id === openId) ?? null;

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
          <option value="Expired">Đã hết hạn</option>
        </FilterSelect>
      </div>

      {loadState === "loading" && (
        <SurfaceState variant="loading" title="Đang tải danh sách hoá đơn…" />
      )}

      {loadState === "error" && (
        <SurfaceState
          variant="error"
          title="Không tải được danh sách hoá đơn"
          description={loadError}
          action={{ label: "Thử lại", onClick: () => setReloadKey((key) => key + 1) }}
        />
      )}

      {loadState === "ready" && invoices.length === 0 && (
        <SurfaceState
          variant="empty"
          title="Chưa có hoá đơn nào"
          description="Hoá đơn sẽ xuất hiện khi bạn đồng ý đề xuất khoang hoặc phát sinh khoản thu khác."
          action={{ label: "Xem kho", onClick: () => navigate("/units") }}
        />
      )}

      {loadState === "ready" && invoices.length > 0 && (
        <>
          {list.length === 0 ? (
            <SurfaceState variant="empty" title="Không có hoá đơn phù hợp bộ lọc" />
          ) : (
            <div className="grid gap-2">
              {list.map((invoice) => {
                const meta = STATUS_META[invoice.status];
                return (
                  <button
                    key={invoice.id}
                    className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,0.7fr)_auto] items-center gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle max-[760px]:grid-cols-1 max-[760px]:gap-2"
                    onClick={() => setOpenId(invoice.id)}
                  >
                    <div>
                      <span className="text-label text-muted">
                        {invoice.code} · {INVOICE_TYPE_LABEL[invoice.type]}
                      </span>
                      <h2 className="mt-1 text-section text-ink">{invoice.title}</h2>
                    </div>
                    <span className="flex items-center gap-1.5 text-label text-muted">
                      <CalendarClock size={14} /> Hạn{" "}
                      {invoice.dueDate ? formatDate(invoice.dueDate) : "—"}
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
        </>
      )}

      {openInvoice && (
        <DetailPanel
          title={openInvoice.title}
          eyebrow={`${openInvoice.code} · ${INVOICE_TYPE_LABEL[openInvoice.type]}`}
          onClose={() => setOpenId(null)}
        >
          <p className="text-small leading-relaxed text-muted">
            {openInvoice.description ?? "Không có mô tả."}
          </p>
          <dl className="mt-5 grid gap-2.5">
            <DetailRow label="Khoang liên quan" value={openInvoice.unitCode ?? "—"} />
            <DetailRow label="Cơ sở" value={openInvoice.facilityName ?? "—"} />
            <DetailRow label="Số tiền" value={`${formatPrice(openInvoice.amount)} đ`} />
            <DetailRow
              label="Hạn thanh toán"
              value={openInvoice.dueDate ? formatDate(openInvoice.dueDate) : "—"}
            />
          </dl>

          {openInvoice.status === "Unpaid" && (
            <div className="mt-5">
              <DemoNotice tone="info">
                Thanh toán trực tuyến sẽ được bật khi API thanh toán sẵn sàng. Hiện tại bạn có thể
                theo dõi trạng thái hoá đơn tại đây.
              </DemoNotice>
            </div>
          )}

          {openInvoice.status === "Paid" && (
            <div className="mt-5">
              <DemoNotice tone="success">Hoá đơn đã được thanh toán.</DemoNotice>
            </div>
          )}

          {openInvoice.status === "Expired" && (
            <div className="mt-5">
              <DemoNotice tone="error">
                Hoá đơn đã hết hạn thanh toán. Vui lòng liên hệ cơ sở để được hỗ trợ.
              </DemoNotice>
            </div>
          )}

          {openInvoice.status === "Unpaid" && openInvoice.orderId !== null && (
            <Button
              className="mt-3 w-full"
              variant="secondary"
              onClick={() => navigate("/my-storage")}
            >
              Về Kho của tôi <ArrowRight size={16} />
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
