import { AlertTriangle, Package, TrendingUp, Warehouse } from "lucide-react";
import { useEffect, useState } from "react";
import type { Navigate } from "../../app/types";
import { formatPrice, unitTypes } from "../../mocks/catalog";
import { FACILITY_OVERDUE_COUNT, fmUnits, type FmUnitStatus } from "../../mocks/fm";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { FilterSelect } from "../../components/ui/FilterSelect";

type PeriodFilter = "7d" | "30d" | "quarter";

const PERIOD_LABEL: Record<PeriodFilter, string> = {
  "7d": "7 ngày qua",
  "30d": "30 ngày qua",
  quarter: "Quý này",
};

const PERIOD_MULTIPLIER: Record<PeriodFilter, number> = {
  "7d": 0.25,
  "30d": 1,
  quarter: 3,
};

const STATUS_LABEL: Record<FmUnitStatus, string> = {
  Available: "Trống",
  OnHold: "Đang giữ",
  Reserved: "Đã đặt trước",
  Rented: "Đang cho thuê",
  Maintenance: "Bảo trì",
};

const STATUS_BAR_COLOR: Record<FmUnitStatus, string> = {
  Available: "bg-success",
  OnHold: "bg-warning",
  Reserved: "bg-warning",
  Rented: "bg-info",
  Maintenance: "bg-muted",
};

function unitMonthlyPrice(unitTypeId: string) {
  return unitTypes.find((type) => type.id === unitTypeId)?.monthlyPrice ?? 0;
}

export function FacilityReportPage({ navigate }: { navigate: Navigate }) {
  const [period, setPeriod] = useState<PeriodFilter>("30d");
  const [typeFilter, setTypeFilter] = useState<string>("all");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const timer = window.setTimeout(() => setLoading(false), 500);
    return () => window.clearTimeout(timer);
  }, [period, typeFilter]);

  const units = fmUnits.filter((unit) => typeFilter === "all" || unit.unitTypeId === typeFilter);
  const total = units.length;
  const rentedUnits = units.filter((unit) => unit.status === "Rented");
  const availableCount = units.filter((unit) => unit.status === "Available").length;
  const occupancyRate = total === 0 ? 0 : Math.round((rentedUnits.length / total) * 100);
  const estimatedRevenue = Math.round(
    rentedUnits.reduce((sum, unit) => sum + unitMonthlyPrice(unit.unitTypeId), 0) *
      PERIOD_MULTIPLIER[period],
  );

  const statusBreakdown = (
    ["Available", "OnHold", "Reserved", "Rented", "Maintenance"] as const
  ).map((status) => ({
    status,
    count: units.filter((unit) => unit.status === status).length,
  }));

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Báo cáo cơ sở</h1>
        <p className="mt-1.5 text-small text-muted">
          Tình hình vận hành của cơ sở bạn phụ trách — không bao gồm số liệu cơ sở khác.
        </p>
      </div>

      <DemoNotice tone="pending">
        Số liệu chỉ tính trong phạm vi cơ sở bạn quản lý — kiểm tra facility phải nằm ở tầng API,
        không chỉ ẩn ở giao diện, để tránh rủi ro xem được số liệu cơ sở khác.
      </DemoNotice>

      <div className="grid grid-cols-2 items-end gap-3 max-[760px]:grid-cols-1">
        <FilterSelect
          label="Khoảng thời gian"
          value={period}
          onChange={(value) => {
            setPeriod(value as PeriodFilter);
            setLoading(true);
          }}
        >
          {(Object.keys(PERIOD_LABEL) as PeriodFilter[]).map((value) => (
            <option key={value} value={value}>
              {PERIOD_LABEL[value]}
            </option>
          ))}
        </FilterSelect>
        <FilterSelect
          label="Loại kho"
          value={typeFilter}
          onChange={(value) => {
            setTypeFilter(value);
            setLoading(true);
          }}
        >
          <option value="all">Tất cả loại kho</option>
          {unitTypes.map((type) => (
            <option key={type.id} value={type.id}>
              {type.name}
            </option>
          ))}
        </FilterSelect>
      </div>

      {loading ? (
        <div className="grid gap-2 bg-surface p-6" aria-label="Đang tính số liệu">
          <span className="h-4 w-1/3 bg-surface-subtle" />
          <span className="h-4 w-2/3 bg-surface-subtle" />
          <span className="h-4 w-1/2 bg-surface-subtle" />
        </div>
      ) : (
        <>
          <div className="grid grid-cols-4 gap-2 max-[900px]:grid-cols-2 max-[520px]:grid-cols-1">
            <button
              className="bg-surface p-5 text-left transition-colors hover:bg-surface-subtle"
              onClick={() => navigate("/fm/storage-units")}
            >
              <span className="flex items-center gap-1.5 text-label text-muted">
                <Warehouse size={13} /> Tỷ lệ lấp đầy
              </span>
              <strong className="mt-2 block text-stat text-ink">{occupancyRate}%</strong>
              <span className="mt-1 block text-label text-muted">
                {rentedUnits.length}/{total} khoang đang cho thuê
              </span>
            </button>

            <button
              className="bg-surface p-5 text-left transition-colors hover:bg-surface-subtle"
              onClick={() => navigate("/fm/storage-units")}
            >
              <span className="flex items-center gap-1.5 text-label text-muted">
                <Package size={13} /> Khoang trống
              </span>
              <strong className="mt-2 block text-stat text-ink">{availableCount}</strong>
              <span className="mt-1 block text-label text-muted">Sẵn sàng cho thuê ngay</span>
            </button>

            <div className="bg-surface p-5">
              <span className="flex items-center gap-1.5 text-label text-muted">
                <TrendingUp size={13} /> Doanh thu ước tính
              </span>
              <strong className="mt-2 block text-[22px] font-semibold text-ink">
                {formatPrice(estimatedRevenue)} đ
              </strong>
              <span className="mt-1 block text-label text-muted">Theo {PERIOD_LABEL[period]}</span>
            </div>

            <button
              className="bg-surface p-5 text-left transition-colors hover:bg-surface-subtle"
              onClick={() => navigate("/fm/support-requests")}
            >
              <span className="flex items-center gap-1.5 text-label text-danger">
                <AlertTriangle size={13} /> Ca quá hạn
              </span>
              <strong className="mt-2 block text-stat text-ink">{FACILITY_OVERDUE_COUNT}</strong>
              <span className="mt-1 block text-label text-muted">Số liệu mẫu, chờ Flow 6</span>
            </button>
          </div>

          <section className="bg-surface p-5">
            <h2 className="text-[15px] font-semibold text-ink">Phân bố khoang theo trạng thái</h2>
            <div className="mt-4 grid gap-3">
              {statusBreakdown.map(({ status, count }) => (
                <div key={status} className="grid grid-cols-[120px_1fr_40px] items-center gap-3">
                  <span className="text-label text-muted">{STATUS_LABEL[status]}</span>
                  <div className="h-2 bg-surface-subtle">
                    <div
                      className={`h-full ${STATUS_BAR_COLOR[status]}`}
                      style={{ width: total === 0 ? "0%" : `${(count / total) * 100}%` }}
                    />
                  </div>
                  <span className="text-right text-label font-semibold text-ink">{count}</span>
                </div>
              ))}
            </div>
          </section>
        </>
      )}
    </div>
  );
}
