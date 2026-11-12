import { MapPin } from "lucide-react";
import { useEffect, useState } from "react";
import {
  directionsText,
  fetchFacilityLayout,
  locationText,
  type FacilityLayout,
} from "../../app/facilityLayout";
import { FacilityLayoutDiagram } from "./FacilityLayoutDiagram";

/**
 * Vị trí một khoang trong cơ sở cho khách / FS: câu chỉ đường + sơ đồ tầng tô sáng khoang. Sơ đồ public không có
 * trạng thái các khoang khác. Khoang chưa xếp lên sơ đồ thì chỉ báo một dòng.
 */
export function UnitLocationCard({
  facilityId,
  unitCode,
  title = "Vị trí khoang",
  compact = false,
}: {
  facilityId: number | null | undefined;
  unitCode: string | null | undefined;
  title?: string;
  /** Ẩn sơ đồ, chỉ giữ câu vị trí (thẻ hẹp). */
  compact?: boolean;
}) {
  const [layout, setLayout] = useState<FacilityLayout | null>(null);
  const [failed, setFailed] = useState(false);
  const [showMap, setShowMap] = useState(!compact);

  useEffect(() => {
    if (!facilityId) return;
    let active = true;
    fetchFacilityLayout(facilityId)
      .then((value) => active && setLayout(value))
      .catch(() => active && setFailed(true));
    return () => {
      active = false;
    };
  }, [facilityId]);

  if (!facilityId || !unitCode || failed) return null;
  if (!layout) {
    return (
      <div className="animate-pulse bg-surface-subtle p-4 text-small text-muted">
        Đang tải vị trí khoang…
      </div>
    );
  }
  const unit = layout.units.find((slot) => slot.code === unitCode);

  return (
    <div className="step-enter border-l-4 border-brand bg-surface-subtle p-4">
      <span className="inline-flex items-center gap-1.5 text-label text-muted">
        <MapPin size={15} /> {title} · {layout.name}
      </span>
      {unit ? (
        <>
          <strong className="mt-1 block text-section text-ink">
            {unit.code} · {locationText(unit)}
          </strong>
          <p className="mb-0 mt-1 text-small text-muted">{directionsText(unit)}</p>
          {compact && (
            <button
              type="button"
              className="mt-2 text-small font-semibold text-accent underline"
              onClick={() => setShowMap((value) => !value)}
            >
              {showMap ? "Ẩn sơ đồ tầng" : "Xem sơ đồ tầng"}
            </button>
          )}
          {showMap && (
            <div className="mt-3">
              <FacilityLayoutDiagram
                floorCount={layout.floorCount}
                units={layout.units}
                highlightCode={unit.code}
                onlyFloor={unit.floorNo}
              />
            </div>
          )}
        </>
      ) : (
        <p className="mb-0 mt-1 text-small text-muted">
          Khoang {unitCode} chưa được xếp lên sơ đồ cơ sở — nhân viên sẽ hướng dẫn tại quầy.
        </p>
      )}
    </div>
  );
}
