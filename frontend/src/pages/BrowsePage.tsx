import { useState } from "react";
import { ArrowRight, Filter } from "lucide-react";
import { Navigate } from "../app/types";
import { PAGE_CONTAINER } from "../app/layout";
import { formatMoney } from "../app/format";
import {
  fetchCatalog,
  formatArea,
  formatDimensions,
  useResource,
  type UnitTypeOption,
} from "../app/services/customer";
import { Button } from "../components/ui/Button";
import { FilterSelect } from "../components/ui/FilterSelect";
import { SurfaceState } from "../components/ui/SurfaceState";
import { FacilityMapSection } from "../components/domain/FacilityMapSection";
import { FacilityShowcase } from "../components/domain/FacilityShowcase";
import { Reveal } from "../components/ui/Reveal";
import { UnitDetailsDialog } from "../components/domain/UnitDetailsDialog";

export function BrowsePage({ navigate }: { navigate: Navigate }) {
  const catalog = useResource(fetchCatalog);
  const [facilityFilter, setFacilityFilter] = useState(
    () => new URLSearchParams(window.location.search).get("facility") ?? "",
  );
  const [typeFilter, setTypeFilter] = useState(
    () => new URLSearchParams(window.location.search).get("type") ?? "",
  );
  const [sort, setSort] = useState("price-asc");
  const [selectedUnit, setSelectedUnit] = useState<UnitTypeOption | null>(null);

  const data = catalog.data;
  const facilities = data?.facilities ?? [];
  const selectedFacility = facilities.find((item) => String(item.id) === facilityFilter) ?? null;
  const pool = data
    ? facilityFilter
      ? (data.offerings[Number(facilityFilter)] ?? [])
      : data.unitTypes
    : [];
  const filteredTypes = pool
    .filter((unit) => !typeFilter || String(unit.id) === typeFilter)
    .sort((left, right) => {
      const leftPrice = Number(left.monthlyPrice) || 0;
      const rightPrice = Number(right.monthlyPrice) || 0;
      if (sort === "price-desc") return rightPrice - leftPrice;
      if (sort === "area-asc") return (Number(left.area) || 0) - (Number(right.area) || 0);
      return leftPrice - rightPrice;
    });

  function facilitiesOffering(unitTypeId: number) {
    return facilities.filter((facility) => facility.unitTypeIds.includes(unitTypeId));
  }

  function clearFilters() {
    setFacilityFilter("");
    setTypeFilter("");
    setSort("price-asc");
  }

  return (
    <>
      <section className="bg-background pb-16 pt-10">
        <div className={PAGE_CONTAINER}>
          <div className="flex items-end justify-between gap-8 pb-6">
            <div>
              <span className="label text-muted">Danh mục khoang</span>
              <h1 className="mt-2 text-[2.625rem] leading-[1.0] tracking-[-0.02em] text-ink">
                Loại khoang theo cơ sở
              </h1>
              <p className="mt-2 text-small text-muted">
                Giá niêm yết theo tháng. Khoang cụ thể sẽ được cơ sở đề xuất sau khi bạn gửi yêu
                cầu.
              </p>
            </div>
            {data && (
              <span className="text-label text-muted">
                {data.unitTypes.length} loại khoang · {facilities.length} cơ sở
              </span>
            )}
          </div>

          <div className="mb-6">
            <FacilityMapSection
              facilities={facilities}
              selectedId={facilityFilter ? Number(facilityFilter) : null}
              onSelect={(id) => {
                setFacilityFilter(String(id) === facilityFilter ? "" : String(id));
                setTypeFilter("");
              }}
            />
          </div>

          {selectedFacility ? (
            <div key={selectedFacility.id} className="step-enter mb-8">
              <FacilityShowcase
                facility={selectedFacility}
                offerings={data?.offerings[selectedFacility.id] ?? []}
                navigate={navigate}
                withDiagram
              />
            </div>
          ) : (
            facilities.length > 0 && (
              <Reveal className="mb-8">
                <p className="m-0 rounded-xl border border-dashed border-rule-strong p-4 text-center text-small text-muted">
                  Bấm một cơ sở trên bản đồ (hoặc chọn ở ô "Cơ sở" bên dưới) để xem giới thiệu, sơ
                  đồ các tầng và số khoang còn trống.
                </p>
              </Reveal>
            )
          )}

          <div className="grid grid-cols-[1.15fr_1fr_1fr_auto] items-end gap-3 max-[900px]:grid-cols-2 max-[760px]:grid-cols-1">
            <FilterSelect
              label="Cơ sở"
              value={facilityFilter}
              onChange={(value) => {
                setFacilityFilter(value);
                setTypeFilter("");
              }}
            >
              <option value="">Tất cả cơ sở</option>
              {facilities.map((item) => (
                <option key={item.id} value={String(item.id)}>
                  {item.name}
                </option>
              ))}
            </FilterSelect>
            <FilterSelect label="Loại khoang" value={typeFilter} onChange={setTypeFilter}>
              <option value="">Tất cả loại khoang</option>
              {pool.map((item) => (
                <option key={item.id} value={String(item.id)}>
                  {item.name} · {formatArea(item.area)}
                </option>
              ))}
            </FilterSelect>
            <FilterSelect label="Sắp xếp" value={sort} onChange={setSort}>
              <option value="price-asc">Giá thấp → cao</option>
              <option value="price-desc">Giá cao → thấp</option>
              <option value="area-asc">Diện tích nhỏ → lớn</option>
            </FilterSelect>
            <Button variant="secondary" onClick={clearFilters}>
              <Filter size={15} /> Xoá lọc
            </Button>
          </div>

          <div className="mb-2 mt-6 flex items-center justify-between gap-4">
            <span className="label text-muted">
              {data ? `${filteredTypes.length} kết quả phù hợp` : "Đang tải danh mục"}
            </span>
            <span className="label text-muted">Giá niêm yết theo tháng</span>
          </div>

          {catalog.loading && !data ? (
            <div className="grid gap-2" role="status" aria-label="Đang tải">
              {[0, 1, 2].map((index) => (
                <div key={index} className="h-[88px] animate-pulse bg-surface" />
              ))}
            </div>
          ) : catalog.error && !data ? (
            <SurfaceState
              variant="error"
              title="Không tải được danh mục khoang"
              description={catalog.error}
              action={{ label: "Thử lại", onClick: catalog.reload }}
            />
          ) : filteredTypes.length === 0 ? (
            <div className="flex items-center justify-between gap-6 bg-surface p-4 max-[760px]:block">
              <p className="text-small text-ink">
                Không thấy loại khoang phù hợp? Gửi nhu cầu để cơ sở đề xuất phương án khác.
              </p>
              <Button className="max-[760px]:mt-3" onClick={() => navigate("/rental-requests/new")}>
                Gửi yêu cầu <ArrowRight size={16} />
              </Button>
            </div>
          ) : (
            <div className="grid gap-2">
              {filteredTypes.map((unit) => {
                const offering = facilitiesOffering(unit.id);
                return (
                  <div
                    key={unit.id}
                    className="lift grid grid-cols-[64px_minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,0.7fr)_120px] items-center gap-5 bg-surface px-4 py-4 max-[900px]:grid-cols-[56px_minmax(0,1fr)_auto] max-[760px]:gap-3"
                  >
                    <div className="grid h-14 w-14 place-items-center border-2 border-[#101413] bg-brand text-center text-label font-semibold text-signal-ink max-[900px]:row-span-3">
                      {formatArea(unit.area)}
                    </div>
                    <div className="min-w-0 max-[900px]:col-span-2">
                      <strong className="block text-small font-semibold text-ink">
                        {unit.name}
                      </strong>
                      <span className="mt-0.5 block truncate text-label text-muted">
                        Rộng × sâu × cao: {formatDimensions(unit)}
                      </span>
                    </div>
                    <div className="text-small text-muted max-[900px]:hidden">
                      <span className="block text-label text-muted">Có tại</span>
                      {offering.length ? offering.map((item) => item.code).join(" · ") : "—"}
                    </div>
                    <div className="text-small text-ink max-[900px]:hidden">
                      <span className="block text-label text-muted">Giá / tháng</span>
                      {formatMoney(unit.monthlyPrice)}
                    </div>
                    <div className="flex flex-col items-end gap-1 max-[900px]:col-start-3 max-[900px]:row-start-1">
                      <Button variant="secondary" size="sm" onClick={() => setSelectedUnit(unit)}>
                        Xem
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </section>

      {selectedUnit && (
        <UnitDetailsDialog
          unit={selectedUnit}
          facilities={facilitiesOffering(selectedUnit.id)}
          facilityId={facilityFilter ? Number(facilityFilter) : null}
          onClose={() => setSelectedUnit(null)}
          navigate={navigate}
        />
      )}
    </>
  );
}
