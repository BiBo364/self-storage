import { useState } from "react";
import { ArrowRight, ChevronDown, Filter } from "lucide-react";
import { Navigate } from "../app/types";
import { PAGE_CONTAINER } from "../app/layout";
import { facilities, formatPrice, UnitType, unitTypes } from "../mocks/catalog";
import { Button } from "../components/ui/Button";
import { INPUT_ELEMENT, INPUT_WRAP } from "../components/ui/FormField";
import { UnitDetailsDialog } from "../components/domain/UnitDetailsDialog";

function FilterSelect({
  label,
  value,
  onChange,
  children,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  children: React.ReactNode;
}) {
  return (
    <label className="grid gap-1.5">
      <span className="text-label text-muted">{label}</span>
      <div className={INPUT_WRAP}>
        <select
          className={`${INPUT_ELEMENT} pr-6`}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        >
          {children}
        </select>
        <ChevronDown size={15} className="pointer-events-none absolute right-3 text-muted" />
      </div>
    </label>
  );
}

export function BrowsePage({ navigate }: { navigate: Navigate }) {
  const params = new URLSearchParams(window.location.search);
  const [facilityFilter, setFacilityFilter] = useState(params.get("facility") ?? "");
  const [typeFilter, setTypeFilter] = useState(params.get("type") ?? "");
  const [sort, setSort] = useState("price-asc");
  const [selectedUnit, setSelectedUnit] = useState<UnitType | null>(null);

  const facility = facilities.find((item) => item.id === facilityFilter);
  const filteredTypes = unitTypes
    .filter((unit) => !typeFilter || unit.id === typeFilter)
    .filter((unit) => !facility || facility.availableUnitIds.includes(unit.id))
    .sort((left, right) => {
      if (sort === "price-desc") return right.monthlyPrice - left.monthlyPrice;
      if (sort === "area-asc") return parseInt(left.area) - parseInt(right.area);
      return left.monthlyPrice - right.monthlyPrice;
    });

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
                Khoang đang trống
              </h1>
              <p className="mt-2 text-small text-muted">
                Giá tham khảo theo tháng · Cập nhật từ dữ liệu minh hoạ
              </p>
            </div>
            <span className="text-label text-muted">
              {filteredTypes.length} quy mô · {facilities.length} cơ sở
            </span>
          </div>

          <div className="grid grid-cols-[1.15fr_1fr_1fr_auto] items-end gap-3 max-[900px]:grid-cols-2 max-[760px]:grid-cols-1">
            <FilterSelect label="Cơ sở" value={facilityFilter} onChange={setFacilityFilter}>
              <option value="">Tất cả cơ sở</option>
              {facilities.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.district} · {item.name}
                </option>
              ))}
            </FilterSelect>
            <FilterSelect label="Diện tích" value={typeFilter} onChange={setTypeFilter}>
              <option value="">Tất cả diện tích</option>
              {unitTypes.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.area} · {item.name}
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
            <span className="label text-muted">{filteredTypes.length} kết quả phù hợp</span>
            <span className="label text-muted">Giá tham khảo theo tháng</span>
          </div>

          <div className="grid gap-2">
            {filteredTypes.map((unit) => {
              const availableFacilities = facilities.filter((item) =>
                item.availableUnitIds.includes(unit.id),
              );
              return (
                <div
                  key={unit.id}
                  className="grid grid-cols-[64px_minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,0.7fr)_120px] items-center gap-5 bg-surface px-4 py-4 max-[900px]:grid-cols-[56px_minmax(0,1fr)_auto] max-[760px]:gap-3"
                >
                  <div className="grid h-14 w-14 place-items-center border-2 border-[#101413] bg-brand text-label font-semibold text-signal-ink max-[900px]:row-span-3">
                    {unit.area}
                  </div>
                  <div className="min-w-0 max-[900px]:col-span-2">
                    <strong className="block text-small font-semibold text-ink">{unit.name}</strong>
                    <span className="mt-0.5 block truncate text-label text-muted">
                      {unit.capacity} · {unit.idealFor}
                    </span>
                  </div>
                  <div className="text-small text-muted max-[900px]:hidden">
                    <span className="block text-label text-muted">Có tại</span>
                    {availableFacilities.map((item) => item.district).join(" · ")}
                  </div>
                  <div className="text-small text-ink max-[900px]:hidden">
                    <span className="block text-label text-muted">Từ</span>
                    {formatPrice(unit.monthlyPrice)} đ
                  </div>
                  <div className="flex flex-col items-end gap-1 max-[900px]:col-start-3 max-[900px]:row-start-1">
                    <span className="inline-flex items-center gap-1.5 text-label text-success">
                      <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />
                      Còn trống
                    </span>
                    <Button variant="secondary" size="sm" onClick={() => setSelectedUnit(unit)}>
                      Xem
                    </Button>
                  </div>
                </div>
              );
            })}
          </div>

          {filteredTypes.length === 0 && (
            <div className="mt-2 flex items-center justify-between gap-6 bg-surface p-4 max-[760px]:block">
              <p className="text-small text-ink">
                Không thấy khoang phù hợp? Gửi nhu cầu để FM đề xuất phương án khác.
              </p>
              <Button className="max-[760px]:mt-3" onClick={() => navigate("/rental-requests/new")}>
                Gửi yêu cầu <ArrowRight size={16} />
              </Button>
            </div>
          )}
        </div>
      </section>

      {selectedUnit && (
        <UnitDetailsDialog
          unit={selectedUnit}
          onClose={() => setSelectedUnit(null)}
          navigate={navigate}
        />
      )}
    </>
  );
}
