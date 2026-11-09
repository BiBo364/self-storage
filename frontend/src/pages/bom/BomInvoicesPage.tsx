import { useEffect, useState } from "react";
import { formatDate, formatDateTime, formatMoney } from "../../app/format";
import { errorMessage, errorStatus } from "../../app/http";
import {
  INVOICE_STATUS_LABEL,
  INVOICE_TYPE_LABEL,
  fetchBomFacilities,
  fetchBomInvoices,
  type FacilityRow,
  type InvoiceRow,
} from "../../app/services/bom";
import type { Navigate } from "../../app/types";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const STATUS_TONE: Record<string, "success" | "warning" | "danger" | "neutral"> = {
  Paid: "success",
  Unpaid: "warning",
  Expired: "danger",
  Canceled: "neutral",
};

function InvoiceStatus({ status }: { status: string }) {
  return (
    <StatusTag tone={STATUS_TONE[status] ?? "neutral"}>
      {INVOICE_STATUS_LABEL[status] ?? status}
    </StatusTag>
  );
}

/** BOM — hoá đơn toàn chuỗi. */
export function BomInvoicesPage({ navigate }: { navigate: Navigate }) {
  void navigate;
  const [facilities, setFacilities] = useState<FacilityRow[]>([]);
  const [facilityId, setFacilityId] = useState("");
  const [status, setStatus] = useState("");
  const [type, setType] = useState("");
  const [invoices, setInvoices] = useState<InvoiceRow[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);

  useEffect(() => {
    let active = true;
    fetchBomFacilities()
      .then((list) => {
        if (active) setFacilities(list);
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const list = await fetchBomInvoices({
          facilityId: facilityId ? Number(facilityId) : null,
          status,
          type,
        });
        if (active) setInvoices(list);
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [facilityId, status, type, reloadKey]);

  function change(apply: () => void) {
    apply();
    setIsLoading(true);
    setError(null);
  }

  const filtered = facilityId !== "" || status !== "" || type !== "";
  const openInvoice = invoices.find((invoice) => invoice.id === openId) ?? null;

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Hoá đơn toàn chuỗi</h1>
          <p className="mt-1.5 text-small text-muted">Hoá đơn mới nhất của mọi cơ sở.</p>
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-[190px]">
            <FilterSelect
              label="Cơ sở"
              value={facilityId}
              onChange={(value) => change(() => setFacilityId(value))}
            >
              <option value="">Tất cả cơ sở</option>
              {facilities.map((facility) => (
                <option key={facility.id} value={facility.id}>
                  {facility.name}
                </option>
              ))}
            </FilterSelect>
          </div>
          <div className="min-w-[170px]">
            <FilterSelect
              label="Trạng thái"
              value={status}
              onChange={(value) => change(() => setStatus(value))}
            >
              <option value="">Tất cả</option>
              {Object.entries(INVOICE_STATUS_LABEL).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </FilterSelect>
          </div>
          <div className="min-w-[170px]">
            <FilterSelect
              label="Loại"
              value={type}
              onChange={(value) => change(() => setType(value))}
            >
              <option value="">Tất cả</option>
              {/* ForfeitedDeposit chỉ là nhóm doanh thu, không phải loại hoá đơn — lọc luôn rỗng. */}
              {Object.entries(INVOICE_TYPE_LABEL)
                .filter(([value]) => value !== "ForfeitedDeposit")
                .map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
            </FilterSelect>
          </div>
        </div>
      </div>

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải hoá đơn…" />
      ) : error ? (
        error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xem hoá đơn"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được hoá đơn"
            description={error.message}
            action={{ label: "Thử lại", onClick: () => change(() => setReloadKey((k) => k + 1)) }}
          />
        )
      ) : invoices.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={filtered ? "Không có hoá đơn khớp bộ lọc" : "Chưa có hoá đơn nào"}
          description={filtered ? "Bỏ bớt bộ lọc để xem thêm hoá đơn." : undefined}
          action={
            filtered
              ? {
                  label: "Xoá bộ lọc",
                  onClick: () =>
                    change(() => {
                      setFacilityId("");
                      setStatus("");
                      setType("");
                    }),
                }
              : undefined
          }
        />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[860px] border-separate border-spacing-y-1.5 text-small">
            <thead>
              <tr className="bg-ink text-left text-label text-background">
                <th className="px-3.5 py-2.5 font-semibold">Mã hoá đơn</th>
                <th className="px-3.5 py-2.5 font-semibold">Loại</th>
                <th className="px-3.5 py-2.5 font-semibold">Khách hàng</th>
                <th className="px-3.5 py-2.5 font-semibold">Cơ sở · khoang</th>
                <th className="px-3.5 py-2.5 text-right font-semibold">Số tiền</th>
                <th className="px-3.5 py-2.5 font-semibold">Tạo ngày</th>
                <th className="px-3.5 py-2.5 font-semibold">Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {invoices.map((invoice) => (
                <tr
                  key={invoice.id}
                  className="cursor-pointer bg-surface transition-colors hover:bg-surface-subtle"
                  onClick={() => setOpenId(invoice.id)}
                >
                  <td className="px-3.5 py-3 font-medium text-ink">
                    <button
                      className="border-0 bg-transparent p-0 text-left font-medium text-ink hover:underline"
                      onClick={(event) => {
                        event.stopPropagation();
                        setOpenId(invoice.id);
                      }}
                    >
                      {invoice.code}
                    </button>
                  </td>
                  <td className="px-3.5 py-3 text-muted">
                    {INVOICE_TYPE_LABEL[invoice.type] ?? invoice.type}
                  </td>
                  <td className="px-3.5 py-3 text-ink">
                    {invoice.customerName ?? invoice.customerEmail ?? "—"}
                  </td>
                  <td className="px-3.5 py-3 text-muted">
                    {invoice.facilityName ?? "—"}
                    {invoice.unitCode ? ` · ${invoice.unitCode}` : ""}
                  </td>
                  <td className="px-3.5 py-3 text-right font-medium tabular-nums text-ink">
                    {formatMoney(invoice.amount)}
                  </td>
                  <td className="px-3.5 py-3 text-muted">{formatDate(invoice.createdAt)}</td>
                  <td className="px-3.5 py-3">
                    <InvoiceStatus status={invoice.status} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {openInvoice && (
        <DetailPanel
          title={openInvoice.code}
          eyebrow={INVOICE_TYPE_LABEL[openInvoice.type] ?? openInvoice.type}
          onClose={() => setOpenId(null)}
        >
          <div className="mb-4">
            <InvoiceStatus status={openInvoice.status} />
          </div>
          <dl className="m-0 grid gap-2 text-small">
            {[
              ["Nội dung", openInvoice.title ?? "—"],
              ["Số tiền", formatMoney(openInvoice.amount)],
              ["Khách hàng", openInvoice.customerName ?? "—"],
              ["Email", openInvoice.customerEmail ?? "—"],
              ["Cơ sở", openInvoice.facilityName ?? "—"],
              ["Khoang", openInvoice.unitCode ?? "—"],
              ["Đơn", openInvoice.orderId !== null ? `#${openInvoice.orderId}` : "—"],
              ["Hợp đồng", openInvoice.contractCode ?? "—"],
              ["Tạo lúc", formatDateTime(openInvoice.createdAt)],
              ["Hạn thanh toán", formatDateTime(openInvoice.dueDate)],
              ["Thanh toán lúc", formatDateTime(openInvoice.paidAt)],
            ].map(([term, value]) => (
              <div key={term} className="flex justify-between gap-3">
                <dt className="text-muted">{term}</dt>
                <dd className="m-0 text-right text-ink">{value}</dd>
              </div>
            ))}
          </dl>
        </DetailPanel>
      )}
    </div>
  );
}
