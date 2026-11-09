import { FormEvent, useCallback, useState } from "react";
import { ArrowLeft, ArrowRight, LockKeyhole } from "lucide-react";
import { Navigate } from "../app/types";
import { PAGE_CONTAINER } from "../app/layout";
import { authGateway } from "../app/auth";
import { formatDate, formatMoney, isoDate } from "../app/format";
import { errorMessage } from "../app/http";
import {
  createRentalRequest,
  fetchFacilities,
  fetchUnitTypes,
  formatArea,
  useResource,
  type RentalRequestCreated,
  type UnitTypeOption,
} from "../app/services/customer";
import { Button } from "../components/ui/Button";
import { DemoNotice } from "../components/ui/DemoNotice";
import {
  FIELD_GROUP,
  FIELD_LABEL,
  FormField,
  INPUT_ELEMENT,
  INPUT_WRAP,
  SelectField,
} from "../components/ui/FormField";
import { SuccessState } from "../components/ui/SuccessState";
import { SurfaceState } from "../components/ui/SurfaceState";

const PUBLIC_PAGE = "bg-background pb-16 pt-10";

const noUnitTypes = (): Promise<UnitTypeOption[]> => Promise.resolve([]);

export function RequestPage({
  navigate,
  embedded = false,
}: {
  navigate: Navigate;
  embedded?: boolean;
}) {
  const session = authGateway.getSession();
  const backPath = embedded ? "/my-storage" : "/units";
  const backLabel = embedded ? "Về Kho của tôi" : "Quay lại danh mục khoang";

  const facilitiesResource = useResource(fetchFacilities);
  const [facilityId, setFacilityId] = useState(
    () => new URLSearchParams(window.location.search).get("facility") ?? "",
  );
  const [unitTypeId, setUnitTypeId] = useState(
    () => new URLSearchParams(window.location.search).get("type") ?? "",
  );
  const loadUnitTypes = useCallback(
    () => (facilityId ? fetchUnitTypes(Number(facilityId)) : noUnitTypes()),
    [facilityId],
  );
  const unitTypesResource = useResource(loadUnitTypes);
  const unitTypes = unitTypesResource.data ?? [];
  const selectedType = unitTypes.find((unit) => String(unit.id) === unitTypeId) ?? null;

  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [created, setCreated] = useState<RentalRequestCreated | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (key: string) => String(form.get(key) ?? "").trim();
    setSubmitting(true);
    setSubmitError(null);
    try {
      const result = await createRentalRequest({
        facilityId: Number(facilityId),
        unitTypeId: Number(unitTypeId),
        customerName: text("name"),
        customerEmail: text("email"),
        customerPhone: text("phone"),
        startDate: text("startDate"),
        period: Number(text("period")),
      });
      setCreated(result);
    } catch (caught) {
      setSubmitError(errorMessage(caught));
    } finally {
      setSubmitting(false);
    }
  }

  if (created) {
    return (
      <section className={embedded ? "" : PUBLIC_PAGE}>
        <div className="mx-auto w-[min(620px,calc(100%-40px))]">
          <SuccessState
            title="Đã nhận nhu cầu."
            description={`Yêu cầu #${created.id} bắt đầu thuê từ ${formatDate(created.startDate)}. Cơ sở sẽ kiểm tra và phản hồi trước ${formatDate(created.expiresAt)} qua email bạn đã nhập.`}
            action={
              <Button className="mt-6" onClick={() => navigate(backPath)}>
                {embedded ? "Về Kho của tôi" : "Xem lại danh mục khoang"} <ArrowRight size={16} />
              </Button>
            }
          />
        </div>
      </section>
    );
  }

  const facilities = facilitiesResource.data ?? [];

  return (
    <section className={embedded ? "" : PUBLIC_PAGE}>
      <div
        className={`${embedded ? "" : PAGE_CONTAINER} grid grid-cols-[0.8fr_1.2fr] items-start gap-16 max-[900px]:grid-cols-1 max-[900px]:gap-8`}
      >
        <div>
          <button
            className="mb-10 inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-small font-semibold text-accent hover:text-ink"
            onClick={() => navigate(backPath)}
          >
            <ArrowLeft size={16} /> {backLabel}
          </button>
          <h1 className="m-0 max-w-[13ch] text-display text-ink">Gửi nhu cầu lưu trữ.</h1>
          <p className="mt-5 max-w-[36ch] text-lead text-muted">
            Chọn cơ sở, loại khoang và thời gian bắt đầu. Chưa cần thanh toán ở bước này.
          </p>
        </div>

        {facilitiesResource.loading && !facilitiesResource.data ? (
          <SurfaceState variant="loading" title="Đang tải danh sách cơ sở…" />
        ) : facilitiesResource.error && !facilitiesResource.data ? (
          <SurfaceState
            variant="error"
            title="Không tải được danh sách cơ sở"
            description={facilitiesResource.error}
            action={{ label: "Thử lại", onClick: facilitiesResource.reload }}
          />
        ) : facilities.length === 0 ? (
          <SurfaceState
            variant="empty"
            title="Chưa có cơ sở nào đang nhận yêu cầu"
            description="Vui lòng quay lại sau."
          />
        ) : (
          <form
            onSubmit={submit}
            className="border border-[var(--container-border)] bg-surface p-6"
          >
            <div>
              <h2 className="text-section text-ink">Thông tin lưu trữ</h2>
              <p className="mt-1 text-small text-muted">Các trường có dấu * là bắt buộc.</p>
            </div>
            <div className="mt-5 grid grid-cols-2 gap-x-4 max-[760px]:grid-cols-1">
              <FormField
                label="Tên doanh nghiệp / người liên hệ"
                name="name"
                required
                placeholder="Công ty ABC / Nguyễn Minh An"
                defaultValue={session?.user.name}
              />
              <FormField
                label="Email liên hệ"
                name="email"
                required
                type="email"
                placeholder="ban@example.com"
                defaultValue={session?.user.email}
              />
              <FormField
                label="Số điện thoại"
                name="phone"
                required
                type="tel"
                placeholder="090 123 4567"
                defaultValue={session?.user.phone}
              />
              <SelectField
                label="Cơ sở mong muốn"
                name="facility"
                required
                value={facilityId}
                onChange={(value) => {
                  setFacilityId(value);
                  setUnitTypeId("");
                }}
                placeholder="Chọn cơ sở"
                options={facilities.map((item) => ({ value: String(item.id), label: item.name }))}
              />
              <SelectField
                label="Loại khoang"
                name="unitType"
                required
                value={unitTypeId}
                onChange={setUnitTypeId}
                placeholder={
                  !facilityId
                    ? "Chọn cơ sở trước"
                    : unitTypesResource.loading
                      ? "Đang tải loại khoang…"
                      : unitTypes.length === 0
                        ? "Cơ sở chưa có loại khoang"
                        : "Chọn loại khoang"
                }
                options={unitTypes.map((item) => ({
                  value: String(item.id),
                  label: `${item.name} · ${formatArea(item.area)} · ${formatMoney(item.monthlyPrice)}/tháng`,
                }))}
              />
              <div className={FIELD_GROUP}>
                <label className={FIELD_LABEL} htmlFor="startDate">
                  Thời điểm bắt đầu
                  <span className="text-danger" aria-hidden="true">
                    {" *"}
                  </span>
                </label>
                <div className={INPUT_WRAP}>
                  <input
                    id="startDate"
                    name="startDate"
                    type="date"
                    required
                    min={isoDate(new Date())}
                    className={INPUT_ELEMENT}
                  />
                </div>
              </div>
              <div className={FIELD_GROUP}>
                <label className={FIELD_LABEL} htmlFor="period">
                  Thời hạn dự kiến
                  <span className="text-danger" aria-hidden="true">
                    {" *"}
                  </span>
                </label>
                <div className={INPUT_WRAP}>
                  <input
                    id="period"
                    name="period"
                    type="number"
                    min={1}
                    step={1}
                    required
                    placeholder="Ví dụ: 6"
                    className={INPUT_ELEMENT}
                  />
                  <span className="shrink-0 text-label text-muted">tháng</span>
                </div>
              </div>
            </div>

            {unitTypesResource.error && (
              <DemoNotice tone="error">
                Không tải được loại khoang: {unitTypesResource.error}
              </DemoNotice>
            )}
            {selectedType && (
              <p className="text-label text-muted">
                Giá niêm yết {selectedType.name}: {formatMoney(selectedType.monthlyPrice)} / tháng.
                Giá thuê chính thức nằm trong đề xuất khoang.
              </p>
            )}
            {submitError && <DemoNotice tone="error">{submitError}</DemoNotice>}

            <div className="mt-4 flex flex-wrap items-center justify-between gap-4">
              <span className="flex items-center gap-1.5 text-label text-muted">
                <LockKeyhole size={14} /> Chưa cần thanh toán ở bước này
              </span>
              <Button type="submit" pending={submitting} disabled={!facilityId || !unitTypeId}>
                Gửi nhu cầu <ArrowRight size={16} />
              </Button>
            </div>
          </form>
        )}
      </div>
    </section>
  );
}
