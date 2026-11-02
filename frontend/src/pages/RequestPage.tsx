import { FormEvent, useEffect, useState } from "react";
import { ArrowLeft, ArrowRight, LockKeyhole } from "lucide-react";
import { Navigate } from "../app/types";
import type { FacilitySummary, UnitTypeSummary } from "../app/types";
import { PAGE_CONTAINER } from "../app/layout";
import { authGateway } from "../app/auth";
import { listFacilities } from "../app/facilities";
import { listUnitTypes } from "../app/unitTypes";
import { createRentalRequest } from "../app/rentalRequests";
import { Button } from "../components/ui/Button";
import { DemoNotice } from "../components/ui/DemoNotice";
import { FormField, SelectField } from "../components/ui/FormField";
import { SuccessState } from "../components/ui/SuccessState";
import { SurfaceState } from "../components/ui/SurfaceState";

const PUBLIC_PAGE = "bg-background pb-16 pt-10";

type LoadState = "loading" | "ready" | "error";

function todayIso() {
  const today = new Date();
  const month = String(today.getMonth() + 1).padStart(2, "0");
  const day = String(today.getDate()).padStart(2, "0");
  return `${today.getFullYear()}-${month}-${day}`;
}

export function RequestPage({
  navigate,
  embedded = false,
}: {
  navigate: Navigate;
  embedded?: boolean;
}) {
  const [submitted, setSubmitted] = useState(false);
  const session = authGateway.getSession();
  const backPath = embedded ? "/my-storage" : "/units";
  const backLabel = embedded ? "Về Kho của tôi" : "Quay lại phương án kho";

  const [facilities, setFacilities] = useState<FacilitySummary[]>([]);
  const [facilitiesState, setFacilitiesState] = useState<LoadState>("loading");
  const [facilitiesError, setFacilitiesError] = useState("");
  const [facilitiesReloadKey, setFacilitiesReloadKey] = useState(0);
  const [facilityId, setFacilityId] = useState("");

  const [unitTypes, setUnitTypes] = useState<UnitTypeSummary[]>([]);
  const [unitTypesState, setUnitTypesState] = useState<"idle" | LoadState>("idle");
  const [unitTypesError, setUnitTypesError] = useState("");
  const [unitTypeId, setUnitTypeId] = useState("");

  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    let cancelled = false;

    async function load() {
      setFacilitiesState("loading");
      setFacilitiesError("");
      try {
        const next = await listFacilities(controller.signal);
        if (cancelled) return;
        setFacilities(next);
        setFacilitiesState("ready");
      } catch (error) {
        if (cancelled || controller.signal.aborted) return;
        setFacilitiesError(
          error instanceof Error ? error.message : "Không thể tải danh sách cơ sở.",
        );
        setFacilitiesState("error");
      }
    }

    void load();
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [facilitiesReloadKey]);

  useEffect(() => {
    if (!facilityId) return;

    const controller = new AbortController();
    let cancelled = false;

    listUnitTypes(Number(facilityId), controller.signal)
      .then((items) => {
        if (cancelled) return;
        setUnitTypes(items);
        setUnitTypesState("ready");
      })
      .catch((error) => {
        if (cancelled || controller.signal.aborted) return;
        setUnitTypesError(
          error instanceof Error ? error.message : "Không thể tải danh sách quy mô kho.",
        );
        setUnitTypesState("error");
      });

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [facilityId]);

  function changeFacility(value: string) {
    setFacilityId(value);
    setUnitTypeId("");
    setUnitTypes([]);
    setUnitTypesError("");
    setUnitTypesState(value ? "loading" : "idle");
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitError("");

    if (!facilityId || !unitTypeId) {
      setSubmitError("Vui lòng chọn cơ sở và quy mô kho.");
      return;
    }

    const data = new FormData(event.currentTarget);
    const period = Number(data.get("period"));
    if (!Number.isInteger(period) || period < 1) {
      setSubmitError("Thời hạn thuê phải là số tháng lớn hơn 0.");
      return;
    }

    setSubmitting(true);
    try {
      await createRentalRequest({
        facilityId: Number(facilityId),
        customerName: String(data.get("name") ?? "").trim(),
        customerEmail: String(data.get("email") ?? "").trim(),
        customerPhone: String(data.get("phone") ?? "").trim(),
        unitTypeId: Number(unitTypeId),
        startDate: String(data.get("startDate") ?? ""),
        period,
      });
      setSubmitted(true);
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : "Không thể gửi yêu cầu.");
    } finally {
      setSubmitting(false);
    }
  }

  if (submitted) {
    return (
      <section className={embedded ? "" : PUBLIC_PAGE}>
        <div className="mx-auto w-[min(620px,calc(100%-40px))]">
          <SuccessState
            title="Đã nhận nhu cầu."
            description="Đội ngũ vận hành sẽ kiểm tra phương án và phản hồi qua email hoặc số điện thoại."
            action={
              <Button className="mt-6" onClick={() => navigate(backPath)}>
                {embedded ? "Về Kho của tôi" : "Xem lại phương án kho"} <ArrowRight size={16} />
              </Button>
            }
          />
        </div>
      </section>
    );
  }

  const unitTypePlaceholder = !facilityId
    ? "Chọn cơ sở trước"
    : unitTypesState === "loading"
      ? "Đang tải quy mô..."
      : unitTypesState === "error"
        ? "Không tải được quy mô"
        : unitTypes.length === 0
          ? "Cơ sở chưa có quy mô"
          : "Chọn quy mô";

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
            Chọn điểm kho, quy mô và thời gian bắt đầu. Chưa cần thanh toán ở bước này.
          </p>
        </div>

        {facilitiesState === "loading" && (
          <SurfaceState variant="loading" title="Đang tải danh sách cơ sở" />
        )}

        {facilitiesState === "error" && (
          <SurfaceState
            variant="error"
            title="Không tải được danh sách cơ sở"
            description={facilitiesError}
            action={{
              label: "Thử lại",
              onClick: () => setFacilitiesReloadKey((key) => key + 1),
            }}
          />
        )}

        {facilitiesState === "ready" && facilities.length === 0 && (
          <SurfaceState
            variant="empty"
            title="Chưa có cơ sở nào đang hoạt động"
            description="Vui lòng quay lại sau hoặc liên hệ cơ sở gần nhất."
          />
        )}

        {facilitiesState === "ready" && facilities.length > 0 && (
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
              <FormField label="Số điện thoại" name="phone" required placeholder="090 123 4567" />
              <SelectField
                label="Điểm kho mong muốn"
                name="facility"
                required
                value={facilityId}
                onChange={changeFacility}
                options={facilities.map((item) => ({
                  value: String(item.id),
                  label: `${item.code} · ${item.name}`,
                }))}
                placeholder="Chọn cơ sở"
              />
              <SelectField
                label="Quy mô kho dự kiến"
                name="unitType"
                required
                value={unitTypeId}
                onChange={setUnitTypeId}
                options={unitTypes.map((item) => ({
                  value: String(item.id),
                  label: `${item.area} m² · ${item.name}`,
                }))}
                placeholder={unitTypePlaceholder}
                disabled={!facilityId || unitTypesState !== "ready" || unitTypes.length === 0}
              />
              <FormField
                label="Thời điểm bắt đầu"
                name="startDate"
                required
                type="date"
                min={todayIso()}
              />
              <FormField
                label="Thời hạn dự kiến"
                name="period"
                required
                type="number"
                min="1"
                placeholder="Ví dụ: 6"
                suffix="tháng"
              />
            </div>

            {unitTypesState === "error" && <DemoNotice tone="error">{unitTypesError}</DemoNotice>}
            {unitTypesState === "ready" && unitTypes.length === 0 && (
              <DemoNotice tone="info">Cơ sở này chưa có quy mô kho đang cung cấp.</DemoNotice>
            )}
            {submitError && <DemoNotice tone="error">{submitError}</DemoNotice>}

            <div className="mt-2 flex flex-wrap items-center justify-between gap-4">
              <span className="flex items-center gap-1.5 text-label text-muted">
                <LockKeyhole size={14} /> Chưa cần thanh toán ở bước này
              </span>
              <Button
                type="submit"
                pending={submitting}
                disabled={!facilityId || !unitTypeId || unitTypesState !== "ready"}
              >
                Gửi nhu cầu <ArrowRight size={16} />
              </Button>
            </div>
          </form>
        )}
      </div>
    </section>
  );
}
