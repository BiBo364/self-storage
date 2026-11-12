import { useEffect, useState } from "react";
import {
  ArrowRight,
  Camera,
  Check,
  FileText,
  KeyRound,
  Map as MapIcon,
  Send,
  Wallet,
  Warehouse,
} from "lucide-react";
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
import { FacilityMapSection } from "../components/domain/FacilityMapSection";
import { FacilityShowcase } from "../components/domain/FacilityShowcase";
import { Button } from "../components/ui/Button";
import { SelectField } from "../components/ui/FormField";
import { CountUp, Reveal } from "../components/ui/Reveal";
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

const FEATURES = [
  {
    icon: MapIcon,
    title: "Tìm và so sánh kho",
    copy: "Xem cơ sở trên bản đồ, sơ đồ từng tầng, số khoang còn trống và giá niêm yết — không cần đăng nhập.",
  },
  {
    icon: Send,
    title: "Gửi nhu cầu",
    copy: "Chọn cơ sở, loại khoang, ngày bắt đầu và số tháng. Quản lý cơ sở chọn khoang cụ thể và gửi đề xuất cho bạn.",
  },
  {
    icon: Wallet,
    title: "Đặt cọc & nhận kho",
    copy: "Đồng ý đề xuất, đặt cọc trực tuyến, hẹn lịch nhận kho; nhân viên đối chiếu giấy tờ và bàn giao tại chỗ.",
  },
  {
    icon: Warehouse,
    title: "Quản lý kho đang thuê",
    copy: "Xem hợp đồng và hoá đơn, gia hạn, báo sự cố hoặc yêu cầu trả kho ngay trên tài khoản của bạn.",
  },
];

const TRUST = [
  {
    icon: KeyRound,
    title: "Truy cập riêng",
    copy: "Mã số chỉ bạn biết, hoặc chìa khoá cơ giao tận tay khi nhận kho.",
  },
  {
    icon: Camera,
    title: "Biên bản có ảnh",
    copy: "Hiện trạng khoang được ghi lại bằng ảnh lúc nhận và lúc trả kho.",
  },
  {
    icon: FileText,
    title: "Hợp đồng rõ ràng",
    copy: "Xem và tải PDF hợp đồng bất cứ lúc nào; giá thuê chốt lúc ký.",
  },
];

const USE_CASES = [
  "Chuyển nhà, sửa nhà",
  "Đồ theo mùa, xe đạp, đồ thể thao",
  "Hàng tồn của shop online",
  "Hồ sơ, thiết bị văn phòng",
];

const STEPS = [
  ["Gửi nhu cầu", "Điền cơ sở, loại khoang và thời hạn. Không cần tài khoản."],
  ["Nhận đề xuất", "Quản lý cơ sở kiểm tra khoang trống và gửi phương án cụ thể."],
  ["Đặt cọc", "Thanh toán cọc trực tuyến trong thời hạn để giữ khoang."],
  ["Nhận kho", "Chọn lịch bàn giao, ký hợp đồng và nhận quyền truy cập tại cơ sở."],
];

function SkeletonRows({ count, height }: { count: number; height: string }) {
  return (
    <div className="grid gap-2" role="status" aria-label="Đang tải">
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className={`${height} animate-pulse bg-surface`} />
      ))}
    </div>
  );
}

function SectionHeading({ label, title, copy }: { label: string; title: string; copy: string }) {
  return (
    <Reveal className="mb-8 grid grid-cols-[minmax(0,1.15fr)_minmax(0,0.85fr)] items-end gap-10 max-[900px]:grid-cols-1 max-[900px]:gap-4">
      <div>
        <span className="label inline-flex items-center gap-2 text-muted">
          <span className="h-2 w-2 rounded-full bg-brand" aria-hidden="true" />
          {label}
        </span>
        <h2 className="mt-2 max-w-[18ch] text-balance text-[clamp(1.75rem,3.6vw,2.625rem)] leading-[1.0] tracking-[-0.02em] text-ink">
          {title}
        </h2>
      </div>
      <p className="max-w-[46ch] text-body leading-relaxed text-muted">{copy}</p>
    </Reveal>
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

  // Vào từ link header "/#about"…: navigate cuộn lên đầu trước, nên chờ một nhịp rồi cuộn tới đúng phần.
  useEffect(() => {
    const id = window.location.hash.slice(1);
    if (!id) return;
    const timer = window.setTimeout(
      () => document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" }),
      350,
    );
    return () => window.clearTimeout(timer);
  }, []);

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
      <SkeletonRows count={2} height="h-[220px]" />
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
      <section className="relative overflow-hidden bg-background">
        <span
          className="hero-glow -left-32 -top-40 h-[460px] w-[460px] bg-[rgba(242,195,0,0.20)]"
          aria-hidden="true"
        />
        <span
          className="hero-glow right-[6%] top-[38%] h-[400px] w-[400px] bg-[rgba(31,90,102,0.26)]"
          style={{ animationDelay: "-7s" }}
          aria-hidden="true"
        />
        <div
          className={`${CONTAINER} relative grid grid-cols-[minmax(0,1.1fr)_minmax(350px,420px)] items-start gap-[clamp(40px,7vw,92px)] pb-16 pt-[clamp(48px,7vw,92px)] max-[900px]:grid-cols-1 max-[900px]:gap-10 max-[760px]:pt-12`}
        >
          <div>
            <p className="hero-line label flex items-center gap-2.5 text-muted">
              <span className="pulse-dot h-2 w-2 rounded-full bg-brand" aria-hidden="true" />
              Tự lưu trữ · TP. Hồ Chí Minh
            </p>
            <h1 className="mt-5 max-w-[14ch] text-display text-ink">
              <span className="hero-line" style={{ animationDelay: "0.08s" }}>
                Kho đúng cỡ.
              </span>
              <span className="hero-line text-accent" style={{ animationDelay: "0.2s" }}>
                Hợp đồng rõ ràng.
              </span>
            </h1>
            <p
              className="hero-line mt-6 max-w-[48ch] text-lead text-muted"
              style={{ animationDelay: "0.32s" }}
            >
              Thuê khoang chứa đồ theo tháng: xem cơ sở trên bản đồ, so sánh loại khoang và số chỗ
              còn trống, rồi gửi nhu cầu — không cần đăng nhập để bắt đầu.
            </p>
            <div
              className="hero-line mt-8 grid max-w-[560px] grid-cols-3 gap-8 max-[760px]:gap-5"
              style={{ animationDelay: "0.44s" }}
            >
              <div>
                <strong className="block whitespace-nowrap text-stat text-ink">
                  {data ? <CountUp value={facilities.length} pad={2} /> : "—"}
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
                  {data ? <CountUp value={unitTypes.length} pad={2} /> : "—"}
                </strong>
                <span className="text-label text-muted">loại khoang</span>
              </div>
            </div>
            <div
              className="hero-line mt-8 flex flex-wrap gap-2.5"
              style={{ animationDelay: "0.56s" }}
            >
              <Button onClick={() => navigate("/units")}>
                Xem kho trên bản đồ <ArrowRight size={16} />
              </Button>
              <Button
                variant="secondary"
                onClick={() =>
                  document.getElementById("about")?.scrollIntoView({ behavior: "smooth" })
                }
              >
                Kho Mộc là gì?
              </Button>
            </div>
          </div>

          <form
            className="pop-enter overflow-hidden border-2 border-[var(--container-border)] bg-surface shadow-[var(--shadow-soft)]"
            style={{ animationDelay: "0.25s" }}
            onSubmit={browseFromFinder}
          >
            <div
              className="hazard-move h-2 w-full"
              style={{ background: HAZARD_STRIPE }}
              aria-hidden="true"
            />
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

      <section id="about" className="scroll-mt-[90px] bg-background py-[clamp(56px,7vw,92px)]">
        <div className={CONTAINER}>
          <SectionHeading
            label="Giới thiệu"
            title="Kho riêng, gần nhà, thuê theo tháng."
            copy="Kho Mộc cho thuê khoang tự lưu trữ tại TP. Hồ Chí Minh: bạn tự cất và lấy đồ bằng mã số hoặc chìa riêng, trả tiền theo tháng. Trang web này giúp bạn chọn kho, gửi nhu cầu, đặt cọc và quản lý mọi thứ sau khi thuê."
          />

          <div className="grid grid-cols-4 gap-3 max-[980px]:grid-cols-2 max-[560px]:grid-cols-1">
            {FEATURES.map(({ icon: Icon, title, copy }, index) => (
              <Reveal key={title} delay={index * 0.08}>
                <div className="lift h-full bg-surface p-5">
                  <span className="grid h-11 w-11 place-items-center rounded-xl bg-brand text-signal-ink">
                    <Icon size={20} />
                  </span>
                  <h3 className="mt-4 text-[1.0625rem] font-semibold text-ink">{title}</h3>
                  <p className="mt-1.5 text-small leading-relaxed text-muted">{copy}</p>
                </div>
              </Reveal>
            ))}
          </div>

          <div className="mt-10 grid grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)] gap-8 max-[900px]:grid-cols-1">
            <Reveal>
              <h3 className="text-section text-ink">Khách thường thuê kho để</h3>
              <div className="mt-3 flex flex-wrap gap-2">
                {USE_CASES.map((item) => (
                  <span
                    key={item}
                    className="lift rounded-full border border-border bg-surface px-3.5 py-1.5 text-small text-ink"
                  >
                    {item}
                  </span>
                ))}
              </div>
            </Reveal>
            <div className="grid grid-cols-3 gap-3 max-[640px]:grid-cols-1">
              {TRUST.map(({ icon: Icon, title, copy }, index) => (
                <Reveal key={title} delay={index * 0.08}>
                  <div className="h-full border-l-4 border-brand bg-surface-subtle p-4">
                    <Icon size={18} className="text-accent" />
                    <strong className="mt-2 block text-small text-ink">{title}</strong>
                    <p className="mb-0 mt-1 text-label font-normal leading-relaxed text-muted">
                      {copy}
                    </p>
                  </div>
                </Reveal>
              ))}
            </div>
          </div>
        </div>
      </section>

      <section
        id="locations"
        className="scroll-mt-[90px] rounded-none bg-surface-subtle py-[clamp(56px,7vw,92px)]"
      >
        <div className={CONTAINER}>
          <SectionHeading
            label="Cơ sở"
            title="Xem kho trước khi chọn."
            copy="Mỗi cơ sở có địa chỉ, giờ mở cửa, số tầng, loại khoang và số chỗ còn trống. Bấm một cơ sở để xem sơ đồ từng tầng."
          />
          {catalogState ?? (
            <div className="grid gap-4">
              <Reveal>
                <FacilityMapSection
                  facilities={facilities}
                  onSelect={(id) => navigate(`/units?facility=${id}`)}
                />
              </Reveal>
              {facilities.map((facility, index) => (
                <Reveal key={facility.id} delay={index * 0.06}>
                  <FacilityShowcase
                    facility={facility}
                    offerings={data?.offerings[facility.id] ?? []}
                    navigate={navigate}
                  />
                </Reveal>
              ))}
            </div>
          )}
        </div>
      </section>

      <section id="sizes" className="scroll-mt-[90px] bg-background py-[clamp(56px,7vw,92px)]">
        <div className={CONTAINER}>
          <SectionHeading
            label="Loại khoang"
            title="Chọn theo diện tích thật."
            copy="Mỗi loại khoang vẽ theo tỷ lệ diện tích. Giá là giá niêm yết theo tháng, chưa gồm phí phát sinh nếu có."
          />
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
            <div className="grid gap-3">
              {unitTypes.map((unit, index) => {
                const area = Number(unit.area) || 0;
                const square = Math.min(90, 36 + area * 7);
                return (
                  <Reveal key={unit.id} delay={index * 0.07}>
                    <div className="lift group grid grid-cols-[160px_minmax(0,1fr)_auto] items-center gap-6 bg-surface p-5 max-[760px]:grid-cols-1 max-[760px]:gap-3">
                      <div
                        className="relative flex h-[118px] w-[160px] items-end overflow-hidden rounded-lg border-2 border-[var(--container-border)] bg-surface-subtle p-3 max-[760px]:w-full"
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
                          className="rounded-md border-2 border-[#101413] bg-brand transition-transform duration-500 ease-out group-hover:scale-110"
                          style={{ width: square, height: square, transformOrigin: "bottom left" }}
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
                  </Reveal>
                );
              })}
            </div>
          )}
        </div>
      </section>

      <section
        id="how-it-works"
        className="scroll-mt-[90px] rounded-none bg-surface-subtle py-[clamp(56px,7vw,92px)]"
      >
        <div className={CONTAINER}>
          <SectionHeading
            label="Quy trình"
            title="Từ nhu cầu đến nhận kho."
            copy="Bốn bước, không có bước ẩn. Mỗi bước cho bạn biết ai đang xử lý và bạn cần làm gì tiếp theo."
          />
          <div className="relative grid grid-cols-4 gap-3 max-[900px]:grid-cols-2 max-[560px]:grid-cols-1">
            <span
              className="pointer-events-none absolute left-[6%] right-[6%] top-[38px] h-[2px] bg-[repeating-linear-gradient(90deg,var(--brand)_0_10px,transparent_10px_18px)] max-[900px]:hidden"
              aria-hidden="true"
            />
            {STEPS.map(([title, copy], index) => (
              <Reveal key={title} delay={index * 0.12}>
                <div className="lift relative h-full bg-surface p-5">
                  <span className="grid h-10 w-10 place-items-center rounded-full border-2 border-brand bg-background font-mono text-small font-bold text-ink">
                    {String(index + 1).padStart(2, "0")}
                  </span>
                  <h3 className="mt-4 text-[1.0625rem] font-semibold text-ink">{title}</h3>
                  <p className="mt-1.5 text-small leading-relaxed text-muted">{copy}</p>
                </div>
              </Reveal>
            ))}
          </div>
        </div>
      </section>

      <section className="relative overflow-hidden bg-[var(--dark)] px-6 py-[clamp(48px,7vw,78px)] text-[var(--on-dark-ink)]">
        <div
          className="hazard-move absolute inset-x-0 top-0 h-2"
          style={{ background: HAZARD_STRIPE }}
          aria-hidden="true"
        />
        <Reveal className={`${CONTAINER} flex items-end justify-between gap-12 max-[900px]:block`}>
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
        </Reveal>
      </section>
    </>
  );
}
