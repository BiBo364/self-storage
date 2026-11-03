import { FormEvent, useState } from "react";
import { ArrowLeft, ArrowRight, LockKeyhole } from "lucide-react";
import { Navigate } from "../app/types";
import { PAGE_CONTAINER } from "../app/layout";
import { authGateway } from "../app/auth";
import { facilities, unitTypes } from "../mocks/catalog";
import { Button } from "../components/ui/Button";
import { FormField, SelectField } from "../components/ui/FormField";
import { SuccessState } from "../components/ui/SuccessState";

const PUBLIC_PAGE = "bg-background pb-16 pt-10";

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

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitted(true);
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

        <form onSubmit={submit} className="border border-[var(--container-border)] bg-surface p-6">
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
              options={facilities.map((item) => ({ value: item.id, label: item.name }))}
            />
            <SelectField
              label="Quy mô kho dự kiến"
              name="unitType"
              required
              options={unitTypes.map((item) => ({
                value: item.id,
                label: `${item.name} · ${item.area}`,
              }))}
            />
            <FormField label="Thời điểm bắt đầu" name="startDate" required type="date" />
            <FormField
              label="Thời hạn dự kiến"
              name="period"
              required
              type="number"
              placeholder="Ví dụ: 6"
              suffix="tháng"
            />
          </div>
          <div className="mt-2 flex flex-wrap items-center justify-between gap-4">
            <span className="flex items-center gap-1.5 text-label text-muted">
              <LockKeyhole size={14} /> Chưa cần thanh toán ở bước này
            </span>
            <Button type="submit">
              Gửi nhu cầu <ArrowRight size={16} />
            </Button>
          </div>
        </form>
      </div>
    </section>
  );
}
