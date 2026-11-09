import { AlertTriangle } from "lucide-react";
import { formatDate, formatDateTime, formatMoney } from "../../app/format";
import {
  fetchOverdueContracts,
  INVOICE_STATUS_LABEL,
  labelOf,
  loadErrorState,
  useFmLoad,
} from "../../app/services/fm";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const TABLE_COLUMNS = "grid-cols-[1.2fr_0.8fr_0.7fr_0.7fr_0.9fr_1.3fr_0.9fr]";

/**
 * Route `/fm/extend-requests`: gia hạn đã tự động (khách tự tạo hoá đơn gia hạn), FM không
 * duyệt nữa — màn này hiển thị hợp đồng quá hạn của cơ sở và phí quá hạn đang cộng dồn.
 */
export function ExtendRequestQueuePage() {
  const overdue = useFmLoad("overdue-contracts", (facility) => fetchOverdueContracts(facility.id));
  const list = overdue.data ?? [];

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Hợp đồng quá hạn</h1>
        <p className="mt-1.5 text-small text-muted">
          Hợp đồng đang hiệu lực đã qua ngày kết thúc tại{" "}
          {overdue.facility ? `cơ sở ${overdue.facility.name}` : "cơ sở của bạn"}. Khách tự gia hạn
          trên ứng dụng; phí quá hạn do hệ thống cộng dồn mỗi giờ.
        </p>
      </div>

      {overdue.error ? (
        <SurfaceState
          {...loadErrorState(overdue.error, "hợp đồng quá hạn")}
          action={{ label: "Thử lại", onClick: overdue.reload }}
        />
      ) : !overdue.data ? (
        <SurfaceState variant="loading" title="Đang tải hợp đồng quá hạn…" />
      ) : list.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Không có hợp đồng quá hạn"
          description="Mọi hợp đồng đang hiệu lực của cơ sở đều còn trong hạn."
        />
      ) : (
        <div className="overflow-x-auto">
          <div className="min-w-[960px]">
            <div
              className={`grid ${TABLE_COLUMNS} gap-4 bg-[var(--dark)] px-3.5 py-2.5 text-label text-[rgba(233,234,230,.62)]`}
            >
              <span>Hợp đồng</span>
              <span>Hết hạn</span>
              <span>Quá hạn</span>
              <span>Ngày tính phí</span>
              <span>Phí cộng dồn</span>
              <span>Hoá đơn quá hạn đang mở</span>
              <span>Trả kho</span>
            </div>
            <div className="mt-1.5 grid gap-1.5">
              {list.map((contract) => (
                <div
                  key={contract.contractId}
                  className={`grid ${TABLE_COLUMNS} items-center gap-4 bg-surface px-3.5 py-3`}
                >
                  <div className="min-w-0">
                    <strong className="block truncate text-small font-semibold text-ink">
                      {contract.contractCode}
                    </strong>
                    <span className="mt-0.5 block text-label text-muted">
                      Khoang {contract.unitCode ?? "—"} · Đơn #{contract.orderId}
                    </span>
                  </div>
                  <span className="text-label text-ink tabular-nums">
                    {formatDate(contract.endDate)}
                  </span>
                  <span className="flex items-center gap-1.5 text-small font-semibold text-danger tabular-nums">
                    <AlertTriangle size={13} /> {contract.daysOverdue} ngày
                  </span>
                  <span className="text-small text-ink tabular-nums">
                    {contract.chargedDays} ngày
                  </span>
                  <span className="text-small font-semibold text-ink tabular-nums">
                    {formatMoney(contract.accrued)}
                  </span>
                  <div className="min-w-0 text-label">
                    {contract.openInvoice ? (
                      <>
                        <span className="block truncate text-ink">
                          {contract.openInvoice.code} · {formatMoney(contract.openInvoice.amount)}
                        </span>
                        <span className="mt-0.5 block text-muted">
                          {labelOf(INVOICE_STATUS_LABEL, contract.openInvoice.status)} · hạn{" "}
                          {formatDateTime(contract.openInvoice.dueDate)}
                        </span>
                      </>
                    ) : (
                      <span className="text-muted">Chưa có</span>
                    )}
                  </div>
                  <span>
                    {contract.returnRequested ? (
                      <StatusTag tone="info">Đã gửi yêu cầu</StatusTag>
                    ) : (
                      <StatusTag tone="warning">Chưa yêu cầu</StatusTag>
                    )}
                  </span>
                </div>
              ))}
            </div>
            <p className="mt-3 text-label text-muted">{list.length} hợp đồng quá hạn</p>
          </div>
        </div>
      )}
    </div>
  );
}
