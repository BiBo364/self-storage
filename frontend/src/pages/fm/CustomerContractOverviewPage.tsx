import { ArrowRight, CalendarClock, LifeBuoy, Wallet } from "lucide-react";
import { useState } from "react";
import type { Navigate } from "../../app/types";
import { fmContracts, type FmContract } from "../../mocks/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

type LifecycleStatus = "Active" | "ExpiringSoon" | "Overdue";
type StatusFilter = "all" | LifecycleStatus;

const LIFECYCLE_LABEL: Record<LifecycleStatus, string> = {
  Active: "Còn hiệu lực",
  ExpiringSoon: "Sắp hết hạn",
  Overdue: "Quá hạn",
};

const LIFECYCLE_TONE: Record<LifecycleStatus, "success" | "warning" | "danger"> = {
  Active: "success",
  ExpiringSoon: "warning",
  Overdue: "danger",
};

const PAYMENT_LABEL: Record<FmContract["paymentStatus"], string> = {
  Paid: "Đã thanh toán",
  Overdue: "Chưa thanh toán",
};

const EXPIRING_SOON_DAYS = 14;

function parseVnDate(value: string) {
  const [day, month, year] = value.split("/").map(Number);
  return new Date(year, month - 1, day);
}

function lifecycleStatus(endDate: string): LifecycleStatus {
  const diffDays = Math.round(
    (parseVnDate(endDate).getTime() - Date.now()) / (1000 * 60 * 60 * 24),
  );
  if (diffDays < 0) return "Overdue";
  if (diffDays <= EXPIRING_SOON_DAYS) return "ExpiringSoon";
  return "Active";
}

export function CustomerContractOverviewPage({ navigate }: { navigate: Navigate }) {
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [openId, setOpenId] = useState<string | null>(null);

  const withStatus = fmContracts
    .map((contract) => ({ ...contract, lifecycle: lifecycleStatus(contract.endDate) }))
    .sort((a, b) => parseVnDate(a.endDate).getTime() - parseVnDate(b.endDate).getTime());

  const list = withStatus.filter(
    (contract) => statusFilter === "all" || contract.lifecycle === statusFilter,
  );
  const openContract = withStatus.find((contract) => contract.id === openId) ?? null;

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Theo dõi khách hàng &amp; hợp đồng</h1>
        <p className="mt-1.5 text-small text-muted">
          Toàn bộ hợp đồng đang hiệu lực tại cơ sở của bạn — chỉ xem, các thao tác duyệt thực hiện ở
          từng hàng chờ riêng.
        </p>
      </div>

      <div className="max-w-[320px]">
        <FilterSelect
          label="Trạng thái"
          value={statusFilter}
          onChange={(value) => setStatusFilter(value as StatusFilter)}
        >
          <option value="all">Tất cả trạng thái</option>
          <option value="Active">Còn hiệu lực</option>
          <option value="ExpiringSoon">Sắp hết hạn</option>
          <option value="Overdue">Quá hạn</option>
        </FilterSelect>
      </div>

      {list.length === 0 ? (
        <SurfaceState variant="empty" title="Không có hợp đồng nào phù hợp bộ lọc" />
      ) : (
        <div className="grid gap-2">
          {list.map((contract) => (
            <button
              key={contract.id}
              className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
              onClick={() => setOpenId(contract.id)}
            >
              <div>
                <strong className="block text-small font-semibold text-ink">
                  {contract.customerName}
                </strong>
                <span className="mt-1 block text-label text-muted">
                  Khoang {contract.unitCode} · {contract.startDate} → {contract.endDate}
                </span>
                {(contract.hasOpenExtendRequest ||
                  contract.hasOpenReturnRequest ||
                  contract.hasOpenSupportRequest) && (
                  <span className="mt-1.5 flex items-center gap-1.5 text-label text-accent">
                    <ArrowRight size={12} /> Đang có yêu cầu mở liên quan
                  </span>
                )}
              </div>
              <div className="flex flex-col items-end gap-1.5">
                <StatusTag tone={LIFECYCLE_TONE[contract.lifecycle]}>
                  {LIFECYCLE_LABEL[contract.lifecycle]}
                </StatusTag>
                <StatusTag tone={contract.paymentStatus === "Paid" ? "success" : "danger"}>
                  {PAYMENT_LABEL[contract.paymentStatus]}
                </StatusTag>
              </div>
            </button>
          ))}
        </div>
      )}

      {openContract && (
        <DetailPanel
          title={openContract.customerName}
          eyebrow={`Hợp đồng · Khoang ${openContract.unitCode}`}
          onClose={() => setOpenId(null)}
        >
          <div className="grid gap-2 text-small text-muted">
            <span>
              Thời hạn: {openContract.startDate} → {openContract.endDate}
            </span>
            <span className="flex items-center gap-2">
              Trạng thái:
              <StatusTag tone={LIFECYCLE_TONE[openContract.lifecycle]}>
                {LIFECYCLE_LABEL[openContract.lifecycle]}
              </StatusTag>
            </span>
            <span>Thanh toán: {PAYMENT_LABEL[openContract.paymentStatus]}</span>
          </div>

          <div className="mt-6 grid gap-2">
            <Button variant="secondary" className="w-full" onClick={() => navigate("/fm/invoices")}>
              <Wallet size={14} /> Xem hoá đơn liên quan
            </Button>
            {openContract.hasOpenExtendRequest && (
              <Button
                variant="secondary"
                className="w-full"
                onClick={() => navigate("/fm/extend-requests")}
              >
                <CalendarClock size={14} /> Có yêu cầu gia hạn đang mở — xem hàng chờ
              </Button>
            )}
            {openContract.hasOpenReturnRequest && (
              <Button
                variant="secondary"
                className="w-full"
                onClick={() => navigate("/fm/return-requests")}
              >
                <ArrowRight size={14} /> Có yêu cầu trả kho đang mở — xem hàng chờ
              </Button>
            )}
            {openContract.hasOpenSupportRequest && (
              <Button
                variant="secondary"
                className="w-full"
                onClick={() => navigate("/fm/support-requests")}
              >
                <LifeBuoy size={14} /> Có yêu cầu hỗ trợ đang mở — xem hàng chờ
              </Button>
            )}
          </div>

          {openContract.lifecycle === "Overdue" && (
            <div className="mt-6">
              <DemoNotice tone="error">
                Hợp đồng đã quá hạn hết hiệu lực — theo dõi để nhắc khách trả kho hoặc gia hạn.
              </DemoNotice>
            </div>
          )}
        </DetailPanel>
      )}
    </div>
  );
}
