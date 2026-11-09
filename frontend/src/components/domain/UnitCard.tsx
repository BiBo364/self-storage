import { ArrowRight } from "lucide-react";
import { formatMoney } from "../../app/format";
import { formatArea, formatDimensions, type UnitTypeOption } from "../../app/services/customer";
import { Navigate } from "../../app/types";

type UnitCardProps = { unit: UnitTypeOption; navigate: Navigate; onDetails?: () => void };

export function UnitCard({ unit, navigate, onDetails }: UnitCardProps) {
  return (
    <article className="bg-surface p-5 transition-colors duration-[180ms] ease hover:bg-surface-subtle">
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-center gap-3">
          <span className="grid h-14 w-14 shrink-0 place-items-center border-2 border-[#101413] bg-brand text-label font-semibold text-signal-ink">
            {formatArea(unit.area)}
          </span>
          <div>
            <h3 className="m-0 text-section text-ink">{unit.name}</h3>
            <p className="m-0 mt-0.5 text-label text-muted">{formatDimensions(unit)}</p>
          </div>
        </div>
        <span className="flex flex-col items-end">
          <strong className="text-data text-ink">{formatMoney(unit.monthlyPrice)}</strong>
          <small className="text-label text-muted">/ tháng</small>
        </span>
      </div>
      <div className="mt-4 flex justify-end">
        <button
          className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-small font-semibold text-accent hover:text-ink"
          onClick={onDetails ?? (() => navigate(`/rental-requests/new?type=${unit.id}`))}
        >
          {onDetails ? "Xem chi tiết" : "Chọn loại này"} <ArrowRight size={15} />
        </button>
      </div>
    </article>
  );
}
