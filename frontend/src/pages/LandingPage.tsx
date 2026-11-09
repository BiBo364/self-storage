import { useEffect, useState } from "react";
import { ArrowRight, Check } from "lucide-react";
import { Navigate } from "../app/types";
import { PAGE_CONTAINER as CONTAINER } from "../app/layout";
import { formatMoney } from "../app/format";
import {
  fetchCatalog,
  formatArea,
  formatDimensions,
  useResource,
  type Catalog,
  type UnitTypeOption,
} from "../app/services/customer";
import { Button } from "../components/ui/Button";
import { SelectField } from "../components/ui/FormField";
import { SurfaceState } from "../components/ui/SurfaceState";

const HAZARD_STRIPE = "repeating-linear-gradient(135deg, #101413 0 11px, var(--brand) 11px 22px)";

function areaRange(unitTypes: UnitTypeOption[]): string {
  const areas = unitTypes
    .map((unit) => Number(unit.area))
    .filter((area) => Number.isFinite(area) && area > 0);
  if (areas.length === 0) return "—";
  const min = Math.min(...areas);
  const max = Math.max(...areas);
  return min === max ? formatArea(min) : `${formatArea(min).replace(" m²", "")}–${formatArea(max)}`;
}

function lowestPrice(unitTypes: UnitTypeOption[]): number | null {
  const prices = unitTypes
    .map((unit) => Number(unit.monthlyPrice))
    .filter((price) => Number.isFinite(price) && price > 0);
  return prices.length ? Math.min(...prices) : null;
}

const SECTIONS = [
  { id: "how-it-works", index: "01", label: "Quy trình" },
  { id: "locations", index: "02", label: "Cơ sở" },
];

function SectionNav() {
  const [active, setActive] = useState<string | null>(null);

  useEffect(() => {
    const elements = SECTIONS.map((section) => document.getElementById(section.id)).filter(
      (element): element is HTMLElement => element !== null,
    );
    if (elements.length === 0) return;

    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((left, right) => right.intersectionRatio - left.intersectionRatio)[0];
        if (visible) setActive(visible.target.id);
      },
      { rootMargin: "-120px 0px -55% 0px", threshold: [0, 0.25, 0.5] },
    );
    elements.forEach((element) => observer.observe(element));
    return () => observer.disconnect();
  }, []);

  function jump(id: string) {
    setActive(id);
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  return (
    <div className="sticky top-[74px] z-10 border-y border-border bg-background/95 backdrop-blur max-[760px]:top-[64px]">
      <div className={`${CONTAINER} flex items-center gap-6 overflow-x-auto py-2.5`}>
        <span className="label shrink-0 text-muted">Trang này</span>
        {SECTIONS.map((section) => (
          <button
            key={section.id}
            type="button"
            aria-current={active === section.id ? "true" : undefined}
            className={`inline-flex shrink-0 items-center gap-2 border-b-2 pb-0.5 text-small font-semibold transition-colors ${
              active === section.id
                ? "border-brand text-ink"
                : "border-transparent text-muted hover:text-ink"
            }`}
            onClick={() => jump(section.id)}
          >
            <span className="font-mono text-label text-muted">{section.index}</span>
            {section.label}
          </button>
        ))}
      </div>
    </div>
  );
}

function SkeletonRows({ count, height }: { count: number; height: string }) {
  return (
    <div className="grid gap-2" role="status" aria-label="Đang tải">
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className={`${height} animate-pulse bg-surface`} />
      ))}
    </div>
  );
}

export function LandingPage({ navigate }: { navigate: Navigate }) {
  const catalog = useResource(fetchCatalog);
  const [facilityId, setFacilityId] = useState("");
  const [typeId, setTypeId] = useState("");
  const data: Catalog | null = catalog.data;
  const facilities = data?.facilities ?? [];
  const unitTypes = data?.unitTypes ?? [];
  const finderTypes =
    data && facilityId ? (data.offerings[Number(facilityId)] ?? []) : (data?.unitTypes ?? []);

  function browseFromFinder(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const params = new URLSearchParams();
    if (facilityId) params.set("facility", facilityId);
    if (typeId) params.set("type", typeId);
    const search = params.toString();
    navigate(`/units${search ? `?${search}` : ""}`);
  }

  const catalogState =
    catalog.loading && !data ? (
      <SkeletonRows count={3} height="h-[68px]" />
    ) : catalog.error && !data ? (
      <SurfaceState
        variant="error"
        title="Không tải được danh sách cơ sở"
        description={catalog.error}
        action={{ label: "Thử lại", onClick: catalog.reload }}
      />
    ) : facilities.length === 0 ? (
      <SurfaceState
        variant="empty"
        title="Chưa có cơ sở nào đang mở"
        description="Bạn vẫn có thể gửi nhu cầu để đội ngũ vận hành liên hệ khi có cơ sở phù hợp."
        action={{ label: "Gửi yêu cầu", onClick: () => navigate("/rental-requests/new") }}
      />
    ) : null;

  return (
    <>
      <section className="bg-background">
        <div
          className={`${CONTAINER} grid grid-cols-[minmax(0,1.1fr)_minmax(350px,420px)] items-start gap-[clamp(40px,7vw,92px)] pb-14 pt-[clamp(48px,7vw,92px)] max-[900px]:grid-cols-1 max-[900px]:gap-10 max-[760px]:pt-12`}
        >
          <div>
            <p className="label flex items-center gap-2.5 text-muted">
              <span className="h-2 w-2 bg-brand" aria-hidden="true" />
              Tự lưu trữ · TP. Hồ Chí Minh
            </p>
            <h1 className="mt-5 max-w-[14ch] text-display text-ink">
              Kho đúng cỡ.
              <span className="block text-accent">Hợp đồng rõ ràng.</span>
            </h1>
            <p className="mt-6 max-w-[46ch] text-lead text-muted">
              Chọn cơ sở, diện tích và thời hạn. Xem giá tham khảo trước khi gửi yêu cầu — không cần
              đăng nhập để bắt đầu.
            </p>
            <div className="mt-8 grid max-w-[560px] grid-cols-3 gap-8 max-[760px]:gap-5">
              <div>
                <strong className="block whitespace-nowrap text-stat text-ink">
                  {data ? String(facilities.length).padStart(2, "0") : "—"}
                </strong>
                <span className="text-label text-muted">cơ sở đang mở</span>
              </div>
              <div>
                <strong className="block whitespace-nowrap text-stat text-ink">
                  {data ? areaRange(unitTypes) : "—"}
                </strong>
                <span className="text-label text-muted">diện tích khoang</span>
              </div>
              <div>
                <strong className="block whitespace-nowrap text-stat text-ink">
                  {data ? String(unitTypes.length).padStart(2, "0") : "—"}
                </strong>
                <span className="text-label text-muted">loại khoang</span>
              </div>
            </div>
          </div>

          <form
            className="border-2 border-[var(--container-border)] bg-surface"
            onSubmit={browseFromFinder}
          >
            <div className="h-2 w-full" style={{ background: HAZARD_STRIPE }} aria-hidden="true" />
            <div className="grid gap-4 p-6">
              <div>
                <span className="label text-muted">Bắt đầu từ điều bạn biết</span>
                <h2 className="mt-1 text-[1.3125rem] leading-[1.15] tracking-[-0.01em] text-ink">
                  Tìm khoang phù hợp
                </h2>
              </div>
              <SelectField
                label="Cơ sở"
                name="facility"
                value={facilityId}
                onChange={(value) => {
                  setFacilityId(value);
                  setTypeId("");
                }}
                placeholder={catalog.loading && !data ? "Đang tải cơ sở…" : "Tất cả cơ sở"}
                options={facilities.map((facility) => ({
                  value: String(facility.id),
                  label: facility.name,
                }))}
              />
              <SelectField
                label="Loại khoang"
                name="type"
                value={typeId}
                onChange={setTypeId}
                placeholder="Tất cả loại khoang"
                options={finderTypes.map((unit) => ({
                  value: String(unit.id),
                  label: `${unit.name} · ${formatArea(unit.area)}`,
                }))}
              />
              <Button type="submit" className="mt-1 w-full">
                Xem khoang <ArrowRight size={16} />
              </Button>
              {catalog.error && !data && (
                <p className="text-label text-danger" role="alert">
                  {catalog.error}
                </p>
              )}
              <p className="flex items-center justify-center gap-1.5 text-label text-muted">
                <Check size={14} className="text-success" aria-hidden="true" />
                Không cần đăng nhập · Giá tham khảo theo tháng
              </p>
            </div>
          </form>
        </div>
      </section>

      <SectionNav />

      <section aria-label="Cơ sở đang mở" className="bg-background">
        <div className={`${CONTAINER} pb-16`}>
          <div className="mb-2 flex items-center justify-between gap-4">
            <span className="label text-muted">Cơ sở đang mở · giá tham khảo</span>
            {data && <span className="label text-muted">{facilities.length} cơ sở</span>}
          </div>
          {catalogState ?? (
            <div className="grid gap-2">
              {facilities.map((facility, index) => {
                const offered = data?.offerings[facility.id] ?? [];
                const from = lowestPrice(offered);
                return (
                  <div
                    key={facility.id}
                    className="grid grid-cols-[32px_minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,0.8fr)_minmax(0,0.7fr)_24px] items-center gap-4 bg-surface px-4 py-4 max-[900px]:grid-cols-[28px_minmax(0,1fr)_auto] max-[760px]:gap-2"
                  >
                    <span className="text-label text-muted">
                      {String(index + 1).padStart(2, "0")}
                    </span>
                    <div className="min-w-0 max-[900px]:col-span-2">
                      <strong className="block text-small font-semibold text-ink">
                        {facility.name}
                      </strong>
                      <span className="mt-0.5 block truncate text-label text-muted">
                        {[facility.address, facility.operatingHours].filter(Boolean).join(" · ")}
                      </span>
                    </div>
                    <span className="text-small text-muted max-[900px]:hidden">
                      {facility.code}
                    </span>
                    <span className="text-small text-ink max-[900px]:hidden">
                      <span className="block text-label text-muted">Từ</span>
                      {from === null ? "—" : formatMoney(from)}
                    </span>
                    <span className="text-label text-muted max-[900px]:hidden">
                      {offered.length} loại khoang
                    </span>
                    <button
                      className="border-0 bg-transparent p-0 text-muted hover:text-accent"
                      onClick={() => navigate(`/units?facility=${facility.id}`)}
                      aria-label={`Xem khoang tại ${facility.name}`}
                    >
                      <ArrowRight size={17} />
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </section>

      <section className="bg-surface-subtle py-[clamp(56px,7vw,92px)]">
        <div className={CONTAINER}>
          <div className="mb-8 grid grid-cols-[minmax(0,1.15fr)_minmax(0,0.85fr)] items-end gap-10 max-[900px]:grid-cols-1 max-[900px]:gap-4">
            <div>
              <span className="label text-muted">Quy mô</span>
              <h2 className="mt-2 max-w-[17ch] text-balance text-[clamp(1.75rem,3.6vw,2.625rem)] leading-[1.0] tracking-[-0.02em] text-ink">
                Chọn theo diện tích thật.
              </h2>
            </div>
            <p className="max-w-[42ch] text-small leading-relaxed text-muted">
              Mỗi loại khoang vẽ theo tỷ lệ diện tích. Giá là giá niêm yết theo tháng, chưa gồm phí
              phát sinh nếu có.
            </p>
          </div>
          {catalog.loading && !data ? (
            <SkeletonRows count={3} height="h-[158px]" />
          ) : catalog.error && !data ? (
            <SurfaceState
              variant="error"
              title="Không tải được danh sách loại khoang"
              description={catalog.error}
              action={{ label: "Thử lại", onClick: catalog.reload }}
            />
          ) : unitTypes.length === 0 ? (
            <SurfaceState variant="empty" title="Chưa có loại khoang nào được công bố" />
          ) : (
            <div className="grid gap-2">
              {unitTypes.map((unit) => {
                const area = Number(unit.area) || 0;
                const square = Math.min(90, 36 + area * 7);
                return (
                  <div
                    key={unit.id}
                    className="grid grid-cols-[160px_minmax(0,1fr)_auto] items-center gap-6 bg-surface p-5 max-[760px]:grid-cols-1 max-[760px]:gap-3"
                  >
                    <div
                      className="relative flex h-[118px] w-[160px] items-end border-2 border-[var(--container-border)] bg-surface-subtle p-3 max-[760px]:w-full"
                      style={{
                        backgroundImage:
                          "linear-gradient(var(--border) 1px, transparent 1px), linear-gradient(90deg, var(--border) 1px, transparent 1px)",
                        backgroundSize: "16px 16px",
                      }}
                    >
                      <span className="absolute right-3 top-2.5 text-label text-muted">
                        {formatArea(unit.area)}
                      </span>
                      <span
                        className="border-2 border-[#101413] bg-brand"
                        style={{ width: square, height: square }}
                        aria-hidden="true"
                      />
                    </div>
                    <div>
                      <h3 className="text-section text-ink">{unit.name}</h3>
                      <p className="mt-1 text-small text-muted">
                        Kích thước (rộng × sâu × cao): {formatDimensions(unit)}
                      </p>
                    </div>
                    <div className="flex flex-col items-end gap-1 max-[760px]:items-start">
                      <div className="flex items-baseline gap-2">
                        <strong className="text-stat text-ink">
                          {formatMoney(unit.monthlyPrice)}
                        </strong>
                        <span className="text-label text-muted">/ tháng</span>
                      </div>
                      <button
                        className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-small font-semibold text-accent hover:text-ink"
                        onClick={() => navigate(`/units?type=${unit.id}`)}
                      >
                        Xem khoang <ArrowRight size={15} />
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </section>

      <section
        id="how-it-works"
        className="scroll-mt-[120px] bg-background py-[clamp(56px,7vw,92px)]"
      >
        <div className={CONTAINER}>
          <div className="mb-8 grid grid-cols-[minmax(0,1.15fr)_minmax(0,0.85fr)] items-end gap-10 max-[900px]:grid-cols-1 max-[900px]:gap-4">
            <div>
              <span className="label text-muted">Quy trình</span>
              <h2 className="mt-2 max-w-[14ch] text-balance text-[clamp(1.75rem,3.6vw,2.625rem)] leading-[1.0] tracking-[-0.02em] text-ink">
                Từ nhu cầu đến nhận kho.
              </h2>
            </div>
            <p className="max-w-[42ch] text-small leading-relaxed text-muted">
              Bốn bước, không có bước ẩn. Mỗi bước cho bạn biết ai đang xử lý và bạn cần làm gì tiếp
              theo.
            </p>
          </div>
          <div className="grid grid-cols-4 gap-2 max-[900px]:grid-cols-2 max-[760px]:grid-cols-1">
            {[
              ["Gửi nhu cầu", "Điền cơ sở, loại khoang và thời hạn. Không cần tài khoản."],
              ["Nhận đề xuất", "Quản lý cơ sở kiểm tra khoang trống và gửi phương án cụ thể."],
              ["Đặt cọc", "Thanh toán cọc trong thời hạn để giữ khoang."],
              ["Nhận kho", "Chọn lịch bàn giao, ký hợp đồng và nhận quyền truy cập tại cơ sở."],
            ].map(([title, copy]) => (
              <div key={title} className="bg-surface p-5">
                <span className="mb-3 block h-2 w-2 bg-brand" aria-hidden="true" />
                <h3 className="text-[16px] font-semibold text-ink">{title}</h3>
                <p className="mt-1.5 text-small leading-relaxed text-muted">{copy}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section
        id="locations"
        className="scroll-mt-[120px] bg-surface-subtle py-[clamp(56px,7vw,92px)]"
      >
        <div className={CONTAINER}>
          <div className="mb-8 grid grid-cols-[minmax(0,1.15fr)_minmax(0,0.85fr)] items-end gap-10 max-[900px]:grid-cols-1 max-[900px]:gap-4">
            <div>
              <span className="label text-muted">Cơ sở</span>
              <h2 className="mt-2 max-w-[14ch] text-balance text-[clamp(1.75rem,3.6vw,2.625rem)] leading-[1.0] tracking-[-0.02em] text-ink">
                Xem nơi hàng đến.
              </h2>
            </div>
            <p className="max-w-[42ch] text-small leading-relaxed text-muted">
              Địa chỉ, giờ hoạt động và diện tích đang có. Chọn cơ sở để xem khoang cụ thể.
            </p>
          </div>
          {catalogState ?? (
            <>
              <div className="mb-2 grid grid-cols-[1.25fr_1.55fr_0.95fr_0.8fr_120px] gap-5 px-4 text-label text-muted max-[900px]:hidden">
                <span>Cơ sở</span>
                <span>Địa chỉ</span>
                <span>Giờ hoạt động</span>
                <span>Diện tích</span>
                <span />
              </div>
              <div className="grid gap-2">
                {facilities.map((facility) => (
                  <div
                    key={facility.id}
                    className="grid grid-cols-[1.25fr_1.55fr_0.95fr_0.8fr_120px] items-center gap-5 bg-surface p-4 max-[900px]:grid-cols-1 max-[900px]:gap-2"
                  >
                    <div>
                      <strong className="block text-small font-semibold text-ink">
                        {facility.name}
                      </strong>
                      <span className="text-label text-muted">{facility.code}</span>
                    </div>
                    <span className="text-small text-muted">{facility.address ?? "—"}</span>
                    <span className="text-small text-muted">{facility.operatingHours ?? "—"}</span>
                    <span className="text-small text-ink">
                      {areaRange(data?.offerings[facility.id] ?? [])}
                    </span>
                    <Button
                      variant="secondary"
                      size="sm"
                      className="whitespace-nowrap"
                      onClick={() => navigate(`/units?facility=${facility.id}`)}
                    >
                      Xem khoang
                    </Button>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </section>

      <section className="bg-[var(--dark)] px-6 py-[clamp(48px,7vw,78px)] text-[var(--on-dark-ink)]">
        <div className={`${CONTAINER} flex items-end justify-between gap-12 max-[900px]:block`}>
          <div>
            <span className="label text-[var(--on-dark-border-hover)]">
              Chưa chắc cần bao nhiêu?
            </span>
            <h2 className="mt-3 max-w-[19ch] text-[2.75rem] leading-[0.95] tracking-[-0.02em] text-[var(--on-dark-ink)] max-[760px]:text-[2.25rem]">
              Gửi nhu cầu. Cơ sở sẽ đề xuất khoang phù hợp.
            </h2>
          </div>
          <div className="flex shrink-0 gap-2.5 max-[900px]:mt-7">
            <Button onClick={() => navigate("/rental-requests/new")}>
              Gửi yêu cầu đặt kho <ArrowRight size={16} />
            </Button>
            <Button variant="onDark" onClick={() => navigate("/units")}>
              Xem khoang
            </Button>
          </div>
        </div>
      </section>
    </>
  );
}
