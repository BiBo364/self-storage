import { useEffect, useState } from "react";
import { formatDate, formatMoney } from "../../app/format";
import { errorMessage, errorStatus } from "../../app/http";
import {
  INVOICE_TYPE_LABEL,
  fetchBomFacilities,
  fetchBomReport,
  type BomReport,
  type FacilityRow,
} from "../../app/services/bom";
import type { Navigate } from "../../app/types";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { SurfaceState } from "../../components/ui/SurfaceState";

const UNIT_STATUS_LABEL: Record<string, string> = {
  Available: "Trống",
  Reserved: "Đang giữ",
  Rented: "Đang thuê",
  Maintenance: "Bảo trì",
};
const UNIT_STATUS_ORDER = ["Available", "Reserved", "Rented", "Maintenance"];

const DATE_INPUT =
  "min-h-[42px] rounded-lg border border-rule-strong bg-surface px-3 text-small text-ink outline-0 focus:border-brand";

function monthLabel(month: string) {
  const [year, value] = month.split("-");
  return `${value}/${year}`;
}

/** Một chuỗi bar ngang đơn sắc (một series → không cần chú giải). */
function BarList({
  rows,
}: {
  rows: { key: string; label: string; value: number; hint: string }[];
}) {
  const max = Math.max(0, ...rows.map((row) => row.value));
  return (
    <div className="grid gap-2.5">
      {rows.map((row) => (
        <div
          key={row.key}
          className="grid grid-cols-[96px_minmax(0,1fr)_132px] items-center gap-3 max-[760px]:grid-cols-[80px_minmax(0,1fr)]"
          title={`${row.label}: ${formatMoney(row.value)} · ${row.hint}`}
        >
          <span className="text-label text-muted">{row.label}</span>
          <div className="h-3 bg-surface-subtle">
            <div
              className="h-full rounded-r-[4px] bg-info"
              style={{ width: max > 0 ? `${(row.value / max) * 100}%` : "0%" }}
            />
          </div>
          <span className="text-right text-small font-medium tabular-nums text-ink max-[760px]:col-span-2 max-[760px]:text-left">
            {formatMoney(row.value)}
          </span>
        </div>
      ))}
    </div>
  );
}

/** BOM — tổng quan doanh thu toàn chuỗi. */
export function BomDashboardPage({ navigate }: { navigate: Navigate }) {
  void navigate;
  const [facilities, setFacilities] = useState<FacilityRow[]>([]);
  const [facilityId, setFacilityId] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [report, setReport] = useState<BomReport | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

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
        const next = await fetchBomReport({
          facilityId: facilityId ? Number(facilityId) : null,
          from,
          to,
        });
        if (active) setReport(next);
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [facilityId, from, to, reloadKey]);

  function change(apply: () => void) {
    apply();
    setIsLoading(true);
    setError(null);
  }

  function retry() {
    change(() => setReloadKey((key) => key + 1));
  }

  const monthRows = report
    ? Object.entries(
        report.revenue.reduce<Record<string, { amount: number; count: number }>>((acc, row) => {
          const entry = acc[row.month] ?? { amount: 0, count: 0 };
          entry.amount += Number(row.amount);
          entry.count += row.count;
          acc[row.month] = entry;
          return acc;
        }, {}),
      )
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([month, value]) => ({
          key: month,
          label: monthLabel(month),
          value: value.amount,
          hint: `${value.count} hoá đơn`,
        }))
    : [];

  const typeRows = report
    ? Object.entries(
        report.revenue.reduce<Record<string, { amount: number; count: number }>>((acc, row) => {
          const entry = acc[row.type] ?? { amount: 0, count: 0 };
          entry.amount += Number(row.amount);
          entry.count += row.count;
          acc[row.type] = entry;
          return acc;
        }, {}),
      )
        .sort(([, a], [, b]) => b.amount - a.amount)
        .map(([type, value]) => ({
          key: type,
          label: INVOICE_TYPE_LABEL[type] ?? type,
          value: value.amount,
          hint: `${value.count} hoá đơn`,
        }))
    : [];

  const occupancy = report
    ? Object.values(
        report.occupancy.reduce<
          Record<number, { id: number; name: string; counts: Record<string, number> }>
        >((acc, row) => {
          const entry = acc[row.facilityId] ?? {
            id: row.facilityId,
            name: row.facilityName,
            counts: {},
          };
          entry.counts[row.status] = (entry.counts[row.status] ?? 0) + row.count;
          acc[row.facilityId] = entry;
          return acc;
        }, {}),
      )
    : [];
  const extraStatuses = report
    ? [...new Set(report.occupancy.map((row) => row.status))].filter(
        (status) => !UNIT_STATUS_ORDER.includes(status),
      )
    : [];
  const statusColumns = [...UNIT_STATUS_ORDER, ...extraStatuses];

  const kpis = report
    ? [
        { label: "Doanh thu trong kỳ", value: formatMoney(report.totalRevenue) },
        { label: "Hoá đơn chưa thanh toán", value: formatMoney(report.kpis.unpaidAmount) },
        { label: "Cọc đang giữ", value: formatMoney(report.kpis.depositHeld) },
        { label: "Yêu cầu đặt kho chờ duyệt", value: report.kpis.pendingRequests },
        { label: "Đơn đang mở", value: report.kpis.activeOrders },
        { label: "Hợp đồng hiệu lực", value: report.kpis.activeContracts },
        { label: "Hợp đồng quá hạn", value: report.kpis.overdueContracts },
        { label: "Yêu cầu trả kho mở", value: report.kpis.openReturnRequests },
        { label: "Yêu cầu hỗ trợ mở", value: report.kpis.openSupportRequests },
      ]
    : [];

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Tổng quan doanh thu</h1>
          <p className="mt-1.5 text-small text-muted">
            {report
              ? `Từ ${formatDate(report.from)} đến ${formatDate(report.to)} · doanh thu tính trên hoá đơn đã thanh toán.`
              : "Doanh thu, KPI vận hành và lấp đầy khoang."}
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-[200px]">
            <FilterSelect
              label="Cơ sở"
              value={facilityId}
              onChange={(value) => change(() => setFacilityId(value))}
            >
              <option value="">Toàn chuỗi</option>
              {facilities.map((facility) => (
                <option key={facility.id} value={facility.id}>
                  {facility.name}
                </option>
              ))}
            </FilterSelect>
          </div>
          <label className="grid gap-1.5 text-label text-muted">
            Từ ngày
            <input
              type="date"
              className={DATE_INPUT}
              value={from}
              onChange={(event) => change(() => setFrom(event.target.value))}
            />
          </label>
          <label className="grid gap-1.5 text-label text-muted">
            Đến ngày
            <input
              type="date"
              className={DATE_INPUT}
              value={to}
              onChange={(event) => change(() => setTo(event.target.value))}
            />
          </label>
        </div>
      </div>

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải báo cáo…" />
      ) : error || !report ? (
        error?.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xem báo cáo"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được báo cáo"
            description={error?.message}
            action={{ label: "Thử lại", onClick: retry }}
          />
        )
      ) : (
        <>
          <div className="grid grid-cols-4 gap-2 max-[1100px]:grid-cols-2 max-[560px]:grid-cols-1">
            {kpis.map((kpi) => (
              <div key={kpi.label} className="bg-surface p-4">
                <strong className="block text-stat tabular-nums text-ink">{kpi.value}</strong>
                <span className="text-label text-muted">{kpi.label}</span>
              </div>
            ))}
          </div>

          <div className="grid grid-cols-2 gap-4 max-[1100px]:grid-cols-1">
            <div className="grid gap-4 bg-surface p-5">
              <SectionTitle>Doanh thu theo tháng</SectionTitle>
              {monthRows.length === 0 ? (
                <p className="m-0 text-small text-muted">Chưa có hoá đơn thanh toán trong kỳ.</p>
              ) : (
                <BarList rows={monthRows} />
              )}
            </div>
            <div className="grid gap-4 bg-surface p-5">
              <SectionTitle>Doanh thu theo loại hoá đơn</SectionTitle>
              {typeRows.length === 0 ? (
                <p className="m-0 text-small text-muted">Chưa có hoá đơn thanh toán trong kỳ.</p>
              ) : (
                <BarList rows={typeRows} />
              )}
            </div>
          </div>

          <div className="grid gap-4 bg-surface p-5">
            <SectionTitle>Lấp đầy khoang</SectionTitle>
            {occupancy.length === 0 ? (
              <p className="m-0 text-small text-muted">Chưa có khoang nào.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[640px] border-separate border-spacing-y-1.5 text-small">
                  <thead>
                    <tr className="bg-ink text-left text-label text-background">
                      <th className="px-3.5 py-2.5 font-semibold">Cơ sở</th>
                      {statusColumns.map((status) => (
                        <th key={status} className="px-3.5 py-2.5 text-right font-semibold">
                          {UNIT_STATUS_LABEL[status] ?? status}
                        </th>
                      ))}
                      <th className="px-3.5 py-2.5 text-right font-semibold">Tổng</th>
                      <th className="px-3.5 py-2.5 font-semibold">Tỷ lệ đang thuê</th>
                    </tr>
                  </thead>
                  <tbody>
                    {occupancy.map((facility) => {
                      const total = Object.values(facility.counts).reduce((a, b) => a + b, 0);
                      const rented = facility.counts.Rented ?? 0;
                      const rate = total > 0 ? Math.round((rented / total) * 100) : 0;
                      return (
                        <tr key={facility.id} className="bg-surface-subtle">
                          <td className="px-3.5 py-2.5 text-ink">{facility.name}</td>
                          {statusColumns.map((status) => (
                            <td key={status} className="px-3.5 py-2.5 text-right tabular-nums">
                              {facility.counts[status] ?? 0}
                            </td>
                          ))}
                          <td className="px-3.5 py-2.5 text-right font-semibold tabular-nums">
                            {total}
                          </td>
                          <td className="px-3.5 py-2.5">
                            <div className="flex items-center gap-2">
                              <div className="h-2 w-24 bg-surface">
                                <div className="h-full bg-info" style={{ width: `${rate}%` }} />
                              </div>
                              <span className="tabular-nums">{rate}%</span>
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
