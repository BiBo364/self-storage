import { CalendarDays, TrendingUp } from "lucide-react";
import { useState } from "react";
import { formatDate, formatMoney } from "../../app/format";
import {
  fetchFacilityReport,
  INVOICE_TYPE_LABEL,
  labelOf,
  loadErrorState,
  UNIT_STATUS_LABEL,
  useFmLoad,
  type FacilityReport,
} from "../../app/services/fm";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { SurfaceState } from "../../components/ui/SurfaceState";

const UNIT_STATUS_BAR: Record<string, string> = {
  Available: "bg-success",
  Reserved: "bg-warning",
  Rented: "bg-info",
  Maintenance: "bg-muted",
};

function monthLabel(month: string) {
  const [year, value] = month.split("-");
  return value && year ? `${value}/${year}` : month;
}

function groupRevenue(report: FacilityReport) {
  const months = new Map<string, { total: number; rows: FacilityReport["revenue"] }>();
  for (const row of report.revenue) {
    const bucket = months.get(row.month) ?? { total: 0, rows: [] };
    bucket.total += Number(row.amount);
    bucket.rows.push(row);
    months.set(row.month, bucket);
  }
  return Array.from(months.entries());
}

export function FacilityReportPage({ navigate }: { navigate: Navigate }) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const report = useFmLoad(`report:${from}:${to}`, (facility) =>
    fetchFacilityReport(facility.id, { from, to }),
  );

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Báo cáo cơ sở</h1>
        <p className="mt-1.5 text-small text-muted">
          Tình hình vận hành của{" "}
          {report.facility ? `cơ sở ${report.facility.name}` : "cơ sở bạn phụ trách"}. Doanh thu
          tính trên hoá đơn đã thanh toán, theo ngày thanh toán.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <DateInput label="Từ ngày" value={from} onChange={setFrom} />
        <DateInput label="Đến ngày" value={to} onChange={setTo} />
        {(from || to) && (
          <Button
            variant="secondary"
            onClick={() => {
              setFrom("");
              setTo("");
            }}
          >
            6 tháng gần nhất
          </Button>
        )}
      </div>

      {report.error ? (
        <SurfaceState
          {...loadErrorState(report.error, "báo cáo cơ sở")}
          action={{ label: "Thử lại", onClick: report.reload }}
        />
      ) : !report.data ? (
        <SurfaceState variant="loading" title="Đang tính số liệu…" />
      ) : (
        <ReportBody report={report.data} loading={report.loading} navigate={navigate} />
      )}
    </div>
  );
}

function DateInput({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <label className="grid gap-1.5">
      <span className="text-label text-muted">{label}</span>
      <div className={INPUT_WRAP}>
        <CalendarDays size={15} />
        <input
          type="date"
          className={INPUT_ELEMENT}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      </div>
    </label>
  );
}

function ReportBody({
  report,
  loading,
  navigate,
}: {
  report: FacilityReport;
  loading: boolean;
  navigate: Navigate;
}) {
  const { kpis } = report;
  const tiles: { label: string; value: string; path: string }[] = [
    {
      label: "Yêu cầu đặt kho chờ xử lý",
      value: String(kpis.pendingRequests),
      path: "/fm/rental-requests",
    },
    { label: "Đơn thuê đang mở", value: String(kpis.activeOrders), path: "/fm/rental-orders" },
    { label: "Hợp đồng đang hiệu lực", value: String(kpis.activeContracts), path: "/fm/contracts" },
    {
      label: "Hợp đồng quá hạn",
      value: String(kpis.overdueContracts),
      path: "/fm/extend-requests",
    },
    {
      label: "Yêu cầu trả kho đang mở",
      value: String(kpis.openReturnRequests),
      path: "/fm/return-requests",
    },
    {
      label: "Yêu cầu hỗ trợ đang mở",
      value: String(kpis.openSupportRequests),
      path: "/fm/support-requests",
    },
    {
      label: "Hoá đơn chưa thanh toán",
      value: formatMoney(kpis.unpaidAmount),
      path: "/fm/invoices",
    },
    { label: "Cọc đang giữ", value: formatMoney(kpis.depositHeld), path: "/fm/invoices" },
  ];
  const months = groupRevenue(report);
  const maxMonth = Math.max(0, ...months.map(([, bucket]) => bucket.total));
  const occupancyTotal = report.occupancy.reduce((sum, row) => sum + Number(row.count), 0);

  return (
    <div className={`grid gap-6 ${loading ? "opacity-60" : ""}`} aria-busy={loading}>
      <div className="grid grid-cols-4 gap-2 max-[900px]:grid-cols-2 max-[520px]:grid-cols-1">
        {tiles.map((tile) => (
          <button
            key={tile.label}
            className="bg-surface p-5 text-left transition-colors hover:bg-surface-subtle"
            onClick={() => navigate(tile.path)}
          >
            <span className="block text-label text-muted">{tile.label}</span>
            <strong className="mt-2 block text-stat text-ink tabular-nums">{tile.value}</strong>
          </button>
        ))}
      </div>

      <section className="bg-surface p-5">
        <SectionTitle
          action={
            <span className="flex items-center gap-1.5 text-small font-semibold text-ink tabular-nums">
              <TrendingUp size={14} /> {formatMoney(report.totalRevenue)}
            </span>
          }
        >
          Doanh thu {formatDate(report.from)} – {formatDate(report.to)}
        </SectionTitle>
        {months.length === 0 ? (
          <p className="mt-4 text-small text-muted">
            Chưa có hoá đơn nào được thanh toán trong khoảng này.
          </p>
        ) : (
          <div className="mt-4 grid gap-4">
            {months.map(([month, bucket]) => (
              <div key={month}>
                <div className="grid grid-cols-[80px_1fr_140px] items-center gap-3">
                  <span className="text-label font-semibold text-ink tabular-nums">
                    {monthLabel(month)}
                  </span>
                  <div className="h-2 bg-surface-subtle">
                    <div
                      className="h-full bg-brand"
                      style={{
                        width: maxMonth === 0 ? "0%" : `${(bucket.total / maxMonth) * 100}%`,
                      }}
                    />
                  </div>
                  <span className="text-right text-label font-semibold text-ink tabular-nums">
                    {formatMoney(bucket.total)}
                  </span>
                </div>
                <p className="mt-1 pl-[92px] text-label text-muted max-[520px]:pl-0">
                  {bucket.rows
                    .map(
                      (row) =>
                        `${labelOf(INVOICE_TYPE_LABEL, row.type)}: ${formatMoney(row.amount)} (${row.count} hoá đơn)`,
                    )
                    .join(" · ")}
                </p>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="bg-surface p-5">
        <SectionTitle>Khoang theo trạng thái · {occupancyTotal} khoang</SectionTitle>
        {report.occupancy.length === 0 ? (
          <p className="mt-4 text-small text-muted">Cơ sở chưa có khoang nào.</p>
        ) : (
          <div className="mt-4 grid gap-3">
            {report.occupancy.map((row) => (
              <div
                key={`${row.facilityId}-${row.status}`}
                className="grid grid-cols-[120px_1fr_40px] items-center gap-3"
              >
                <span className="text-label text-muted">
                  {labelOf(UNIT_STATUS_LABEL, row.status)}
                </span>
                <div className="h-2 bg-surface-subtle">
                  <div
                    className={`h-full ${UNIT_STATUS_BAR[row.status] ?? "bg-muted"}`}
                    style={{
                      width: occupancyTotal === 0 ? "0%" : `${(row.count / occupancyTotal) * 100}%`,
                    }}
                  />
                </div>
                <span className="text-right text-label font-semibold text-ink tabular-nums">
                  {row.count}
                </span>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
