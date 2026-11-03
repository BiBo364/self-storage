import { ArrowLeft, Box, CalendarDays, Receipt } from "lucide-react";
import { Navigate, View } from "../../app/types";
import { Brand } from "../../components/ui/Brand";
import type { Theme } from "../../components/ui/ThemeToggle";
import { ThemeToggle } from "../../components/ui/ThemeToggle";
import { LoginForm } from "./LoginForm";
import { RegisterForm } from "./RegisterForm";
import { VerifyPanel } from "./VerifyPanel";

const HIGHLIGHTS = [
  { icon: Box, label: "Yêu cầu đặt kho", copy: "Theo dõi phản hồi từ FM." },
  { icon: Receipt, label: "Hợp đồng & thanh toán", copy: "Xem hạn và hoá đơn." },
  { icon: CalendarDays, label: "Lịch nhận kho", copy: "Chọn lịch bàn giao." },
];

export function AuthPage({
  view,
  navigate,
  theme,
  onThemeToggle,
}: {
  view: View;
  navigate: Navigate;
  theme: Theme;
  onThemeToggle: () => void;
}) {
  const mode = view === "register" ? "register" : view === "verify" ? "verify" : "login";

  return (
    <div className="min-h-screen bg-background px-6 pb-16 pt-7 max-[760px]:px-4 max-[760px]:pb-12 max-[760px]:pt-5">
      <div className="mx-auto w-[min(1120px,100%)]">
        <div className="flex items-center justify-between gap-4">
          <Brand onClick={() => navigate("/")} />
          <div className="flex items-center gap-4">
            <button
              className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-small font-semibold text-accent hover:text-ink"
              onClick={() => navigate("/")}
            >
              <ArrowLeft size={15} /> Trang chủ
            </button>
            <ThemeToggle theme={theme} onToggle={onThemeToggle} />
          </div>
        </div>

        <div className="mt-12 grid grid-cols-[minmax(0,1.05fr)_minmax(390px,0.95fr)] gap-16 max-[980px]:gap-10 max-[860px]:mt-8 max-[860px]:grid-cols-1">
          <section className="flex flex-col justify-between gap-12 max-[860px]:gap-7">
            <div>
              <span className="mb-5 block h-2 w-2 bg-brand" aria-hidden="true" />
              <h1 className="m-0 max-w-[13ch] text-display text-ink">
                Quản lý kho
                <br />
                <span className="text-accent">trong một nơi.</span>
              </h1>
              <p className="mt-6 max-w-[42ch] text-lead text-muted">
                Theo dõi yêu cầu lưu trữ, hợp đồng và hoá đơn của bạn tại Kho Mộc.
              </p>
            </div>

            <div className="grid max-w-[400px] gap-2 max-[860px]:hidden">
              {HIGHLIGHTS.map(({ icon: Icon, label, copy }) => (
                <div key={label} className="flex items-center gap-3 bg-surface px-4 py-3">
                  <Icon size={17} className="shrink-0 text-muted" aria-hidden="true" />
                  <div className="min-w-0">
                    <strong className="block text-small font-semibold text-ink">{label}</strong>
                    <span className="block text-label text-muted">{copy}</span>
                  </div>
                </div>
              ))}
            </div>
          </section>

          <section className="self-start border border-[var(--container-border)] bg-surface p-8 max-[760px]:p-5">
            {mode === "login" && <LoginForm navigate={navigate} />}
            {mode === "register" && <RegisterForm navigate={navigate} />}
            {mode === "verify" && <VerifyPanel navigate={navigate} />}
          </section>
        </div>
      </div>
    </div>
  );
}
