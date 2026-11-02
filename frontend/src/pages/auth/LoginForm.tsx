import { FormEvent, useState } from "react";
import { ArrowRight, Eye, EyeOff, LockKeyhole, Mail } from "lucide-react";
import { Navigate, Notice } from "../../app/types";
import { ROLE_HOME } from "../../app/routes";
import { authGateway, type UserRole } from "../../app/auth";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import {
  FIELD_GROUP,
  FIELD_LABEL,
  FormField,
  INPUT_ELEMENT,
  INPUT_WRAP,
} from "../../components/ui/FormField";

const ROLE_PAGE_NAMES: Record<UserRole, string> = {
  ADMIN: "quản trị viên",
  BOM: "BOM",
  FM: "FM",
  FS: "FS",
  CUSTOMER: "khách hàng",
};

export function LoginForm({ navigate }: { navigate: Navigate }) {
  const [notice, setNotice] = useState<Notice>(
    new URLSearchParams(window.location.search).get("verified") === "1"
      ? { tone: "success", message: "Email đã được xác minh. Đăng nhập để tiếp tục." }
      : null,
  );
  const [showPassword, setShowPassword] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const email = String(data.get("email") || "");
    const password = String(data.get("password") || "");
    if (!email || !password) {
      setNotice({ tone: "error", message: "Vui lòng nhập email và mật khẩu để tiếp tục." });
      return;
    }
    setIsSubmitting(true);
    try {
      const result = await authGateway.login(email, password);
      if (!result.ok) {
        setNotice({ tone: "error", message: result.message });
        return;
      }
      const home = ROLE_HOME[result.session.user.role];
      if (!home) {
        const roleName = ROLE_PAGE_NAMES[result.session.user.role];
        authGateway.logout();
        setNotice({ tone: "info", message: `Trang của ${roleName} đang được hoàn thiện.` });
        return;
      }
      navigate(home);
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <>
      <div className="mb-6">
        <h1 className="m-0 text-section text-ink">Đăng nhập</h1>
        <p className="mt-2 text-small text-muted">Quản lý nhu cầu lưu trữ của bạn tại Kho Mộc.</p>
      </div>
      <form className="grid gap-0" onSubmit={submit}>
        <FormField
          label="Email"
          name="email"
          type="email"
          required
          placeholder="ban@example.com"
          icon={<Mail size={17} />}
        />
        <div className={FIELD_GROUP}>
          <div className="flex items-center justify-between gap-4">
            <label className={FIELD_LABEL} htmlFor="password">
              Mật khẩu
            </label>
            <button
              type="button"
              className="self-start border-0 bg-transparent p-0 text-label font-semibold text-accent hover:text-ink"
              onClick={() =>
                setNotice({
                  tone: "info",
                  message: "Nhập email để tiếp tục đặt lại mật khẩu.",
                })
              }
            >
              Quên mật khẩu?
            </button>
          </div>
          <div className={INPUT_WRAP}>
            <LockKeyhole size={17} />
            <input
              id="password"
              name="password"
              type={showPassword ? "text" : "password"}
              placeholder="Nhập mật khẩu"
              autoComplete="current-password"
              className={INPUT_ELEMENT}
            />
            <button
              type="button"
              className="grid place-items-center border-0 bg-transparent p-[3px] text-muted hover:text-ink"
              onClick={() => setShowPassword((current) => !current)}
              aria-label={showPassword ? "Ẩn mật khẩu" : "Hiện mật khẩu"}
            >
              {showPassword ? <EyeOff size={17} /> : <Eye size={17} />}
            </button>
          </div>
        </div>
        <Button type="submit" pending={isSubmitting} className="mt-1 w-full">
          {isSubmitting ? (
            "Đang kiểm tra…"
          ) : (
            <>
              Đăng nhập <ArrowRight size={17} />
            </>
          )}
        </Button>
        {notice && <DemoNotice tone={notice.tone}>{notice.message}</DemoNotice>}
      </form>
      <p className="mt-5 text-center text-small text-muted">
        Bạn chưa có tài khoản?{" "}
        <button
          className="border-0 bg-transparent p-0 font-semibold text-accent hover:text-ink"
          onClick={() => navigate("/register")}
        >
          Đăng ký miễn phí
        </button>
      </p>
    </>
  );
}
