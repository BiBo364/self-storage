import { Navigation } from "lucide-react";
import { directionsUrl } from "../../app/facilityLayout";
import type { CatalogFacility } from "../../app/services/customer";
import { FacilityMap, type MapMarker } from "./FacilityMap";

/**
 * Bản đồ ghim các cơ sở đang hoạt động + danh sách địa chỉ có nút chỉ đường. Bấm ghim / thẻ thì chọn cơ sở đó
 * (`onSelect`), ví dụ để lọc loại khoang theo cơ sở.
 */
export function FacilityMapSection({
  facilities,
  selectedId,
  onSelect,
}: {
  facilities: CatalogFacility[];
  selectedId?: number | null;
  onSelect?: (facilityId: number) => void;
}) {
  const pinned = facilities.filter(
    (facility) => facility.latitude !== null && facility.longitude !== null,
  );
  if (pinned.length === 0) return null;
  const shown = selectedId ? pinned.filter((facility) => facility.id === selectedId) : pinned;
  const markers: MapMarker[] = (shown.length ? shown : pinned).map((facility) => ({
    id: facility.id,
    latitude: Number(facility.latitude),
    longitude: Number(facility.longitude),
    title: facility.name,
    subtitle: [facility.address, facility.operatingHours].filter(Boolean).join(" · "),
  }));

  return (
    <section
      className="grid grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)] gap-3 max-[900px]:grid-cols-1"
      aria-label="Bản đồ cơ sở"
    >
      <FacilityMap markers={markers} height={320} onMarkerClick={(id) => onSelect?.(Number(id))} />
      <div className="grid content-start gap-2">
        {pinned.map((facility) => {
          const active = facility.id === selectedId;
          return (
            <div
              key={facility.id}
              className={`flex items-start justify-between gap-3 border-2 bg-surface p-3 ${
                active ? "border-brand" : "border-transparent"
              }`}
            >
              <button
                type="button"
                className="min-w-0 bg-transparent p-0 text-left"
                onClick={() => onSelect?.(facility.id)}
              >
                <strong className="block text-small font-semibold text-ink">{facility.name}</strong>
                <span className="mt-0.5 block text-label font-normal text-muted">
                  {facility.address ?? "—"}
                  {facility.floorCount > 1 ? ` · ${facility.floorCount} tầng` : ""}
                </span>
              </button>
              <a
                className="inline-flex shrink-0 items-center gap-1 text-label font-semibold text-accent hover:underline"
                href={directionsUrl(Number(facility.latitude), Number(facility.longitude))}
                target="_blank"
                rel="noreferrer"
              >
                <Navigation size={13} /> Chỉ đường
              </a>
            </div>
          );
        })}
      </div>
    </section>
  );
}
