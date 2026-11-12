import { ArrowRight, Clock, Layers, MapPin, Navigation, Warehouse } from "lucide-react";
import { useEffect, useState } from "react";
import { directionsUrl, fetchFacilityLayout, type FacilityLayout } from "../../app/facilityLayout";
import { formatMoney } from "../../app/format";
import type { CatalogFacility, UnitTypeOption } from "../../app/services/customer";
import { formatArea } from "../../app/services/customer";
import type { Navigate } from "../../app/types";
import { Button } from "../ui/Button";
import { FacilityLayoutDiagram } from "./FacilityLayoutDiagram";

type TypeCount = { name: string; total: number; free: number };

function countByType(layout: FacilityLayout | null): TypeCount[] {
  if (!layout) return [];
  const map = new Map<string, TypeCount>();
  for (const unit of layout.units) {
    const row = map.get(unit.unitTypeName) ?? { name: unit.unitTypeName, total: 0, free: 0 };
    row.total += 1;
    if (unit.available) row.free += 1;
    map.set(unit.unitTypeName, row);
  }
  return [...map.values()];
}

/** Sơ đồ public của cơ sở (cache theo trang); lỗi thì coi như chưa có sơ đồ. */
function useLayout(facilityId: number) {
  const [state, setState] = useState<{ id: number; layout: FacilityLayout | null } | null>(null);
  useEffect(() => {
    let active = true;
    fetchFacilityLayout(facilityId)
      .then((layout) => active && setState({ id: facilityId, layout }))
      .catch(() => active && setState({ id: facilityId, layout: null }));
    return () => {
      active = false;
    };
  }, [facilityId]);
  return state?.id === facilityId ? state : null;
}

/**
 * Giới thiệu một cơ sở cho khách: địa chỉ, giờ mở cửa, số tầng, loại khoang + giá, số khoang còn trống.
 * `withDiagram` thêm sơ đồ từng tầng tô theo loại khoang và còn trống / đã có người (trang chọn kho).
 */
export function FacilityShowcase({
  facility,
  offerings,
  navigate,
  withDiagram = false,
}: {
  facility: CatalogFacility;
  offerings: UnitTypeOption[];
  navigate: Navigate;
  withDiagram?: boolean;
}) {
  const loaded = useLayout(facility.id);
  const layout = loaded?.layout ?? null;
  const counts = countByType(layout);
  const total = counts.reduce((sum, row) => sum + row.total, 0);
  const free = counts.reduce((sum, row) => sum + row.free, 0);
  const pinned = facility.latitude !== null && facility.longitude !== null;
  const prices = offerings.map((unit) => Number(unit.monthlyPrice)).filter((price) => price > 0);
  const from = prices.length ? Math.min(...prices) : null;

  return (
    <article className="grid gap-5 bg-surface p-6 max-[560px]:p-4">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <span className="inline-flex items-center gap-2 text-label text-muted">
            <span className="pulse-dot h-2 w-2 rounded-full bg-brand" aria-hidden />
            Cơ sở {facility.code}
          </span>
          <h3 className="mt-1 text-[1.5rem] leading-tight tracking-[-0.01em] text-ink">
            {facility.name}
          </h3>
          <p className="mt-1.5 flex items-start gap-1.5 text-small text-muted">
            <MapPin size={15} className="mt-0.5 shrink-0" /> {facility.address ?? "—"}
          </p>
        </div>
        {from !== null && (
          <div className="text-right">
            <span className="block text-label text-muted">Giá thuê từ</span>
            <strong className="text-stat text-ink">{formatMoney(from)}</strong>
            <span className="text-label text-muted"> / tháng</span>
          </div>
        )}
      </div>

      <dl className="m-0 grid grid-cols-4 gap-3 max-[760px]:grid-cols-2">
        <div className="bg-surface-subtle p-3">
          <dt className="flex items-center gap-1.5 text-label text-muted">
            <Clock size={13} /> Giờ mở cửa
          </dt>
          <dd className="m-0 mt-1 text-small font-semibold text-ink">
            {facility.operatingHours ?? "—"}
          </dd>
        </div>
        <div className="bg-surface-subtle p-3">
          <dt className="flex items-center gap-1.5 text-label text-muted">
            <Layers size={13} /> Số tầng
          </dt>
          <dd className="m-0 mt-1 text-small font-semibold text-ink">{facility.floorCount} tầng</dd>
        </div>
        <div className="bg-surface-subtle p-3">
          <dt className="flex items-center gap-1.5 text-label text-muted">
            <Warehouse size={13} /> Khoang còn trống
          </dt>
          <dd className="m-0 mt-1 text-small font-semibold text-ink">
            {loaded === null ? "…" : total === 0 ? "—" : `${free} / ${total} khoang`}
          </dd>
        </div>
        <div className="bg-surface-subtle p-3">
          <dt className="text-label text-muted">Diện tích</dt>
          <dd className="m-0 mt-1 text-small font-semibold text-ink">
            {offerings.length ? offerings.map((unit) => formatArea(unit.area)).join(" · ") : "—"}
          </dd>
        </div>
      </dl>

      {offerings.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {offerings.map((unit) => {
            const count = counts.find((row) => row.name === unit.name);
            return (
              <span
                key={unit.id}
                className="inline-flex items-center gap-2 rounded-full border border-border px-3 py-1.5 text-small text-ink"
              >
                <strong>{unit.name}</strong>
                <span className="text-muted">{formatMoney(unit.monthlyPrice)}/tháng</span>
                {count && (
                  <span className={count.free > 0 ? "text-success" : "text-danger"}>
                    {count.free > 0 ? `còn ${count.free}` : "hết chỗ"}
                  </span>
                )}
              </span>
            );
          })}
        </div>
      )}

      {withDiagram &&
        (layout && layout.units.length > 0 ? (
          <div className="grid gap-2">
            <span className="text-small font-semibold text-ink">
              Sơ đồ khoang — đứng ở cửa vào nhìn vào trong
            </span>
            <FacilityLayoutDiagram
              key={facility.id}
              floorCount={layout.floorCount}
              units={layout.units}
              colorByType
            />
            <p className="m-0 text-label text-muted">
              Khoang cụ thể do cơ sở đề xuất sau khi bạn gửi nhu cầu; sơ đồ giúp bạn hình dung vị
              trí các loại khoang và còn bao nhiêu chỗ trống.
            </p>
          </div>
        ) : loaded !== null ? (
          <p className="m-0 bg-surface-subtle p-4 text-small text-muted">
            Cơ sở chưa công bố sơ đồ khoang.
          </p>
        ) : (
          <div className="h-[220px] animate-pulse bg-surface-subtle" />
        ))}

      <div className="flex flex-wrap gap-2">
        <Button onClick={() => navigate(`/rental-requests/new?facility=${facility.id}`)}>
          Gửi nhu cầu tại cơ sở này <ArrowRight size={16} />
        </Button>
        {!withDiagram && (
          <Button variant="secondary" onClick={() => navigate(`/units?facility=${facility.id}`)}>
            Xem sơ đồ & loại khoang
          </Button>
        )}
        {pinned && (
          <a
            className="inline-flex min-h-[44px] items-center gap-1.5 px-2 text-small font-semibold text-accent hover:underline"
            href={directionsUrl(Number(facility.latitude), Number(facility.longitude))}
            target="_blank"
            rel="noreferrer"
          >
            <Navigation size={15} /> Chỉ đường
          </a>
        )}
      </div>
    </article>
  );
}
