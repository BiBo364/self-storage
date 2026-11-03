import { FormEvent, useEffect, useState } from "react";
import { ArrowRight, Mail } from "lucide-react";
import { Navigate, Notice } from "../../app/types";
import { authApi } from "../../app/auth";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";

export function VerifyPanel({ navigate }: { navigate: Navigate }) {
  const email = new URLSearchParams(window.location.search).get("email")?.trim() ?? "";
  const [code, setCode] = useState("");
  const [notice, setNotice] = useState<Notice>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isResending, setIsResending] = useState(false);
  const [cooldown, setCooldown] = useState(0);

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = window.setTimeout(() => setCooldown((value) => value - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [cooldown]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!email) return;
    if (!/^\d{6}$/.test(code)) {
      setNotice({ tone: "error", message: "Mã xác minh gồm đúng 6 chữ số." });
      return;
    }
    setIsSubmitting(true);
    const result = await authApi.verifyEmail(email, code);
    setIsSubmitting(false);
    if (!result.ok) {
      setNotice({
        tone: "error",
        message:
          result.status === 429
            ? "Bạn đã nhập sai quá nhiều lần. Gửi lại mã mới rồi thử lại."
            : result.message,
      });
      return;
    }
    navigate("/login?verified=1");
  }

  async function resend() {
    if (!email || cooldown > 0 || isResending) return;
    setIsResending(true);
    const result = await authApi.resendVerification(email);
    setIsResending(false);
    if (!result.ok) {
      setNotice({ tone: "error", message: result.message });
      if (result.status === 429) setCooldown(60);
      return;
    }
    setCooldown(60);
    setNotice({ tone: "success", message: "Đã gửi lại mã xác minh." });
  }

  if (!email) {
    return (
      <div className="text-center">
        <span className="mx-auto mb-5 grid h-14 w-14 place-items-center bg-warning-soft text-warning">
          <Mail size={26} aria-hidden="true" />
        </span>
        <h1 className="m-0 text-section text-ink">Thiếu email xác minh</h1>
        <p className="mx-auto mt-3 max-w-[36ch] text-small text-muted">
          Trang này cần email đã đăng ký. Quay lại đăng ký để nhận mã xác minh.
        </p>
        <Button className="mt-6" onClick={() => navigate("/register")}>
          Đăng ký lại <ArrowRight size={16} />
        </Button>
      </div>
    );
  }

  return (
    <div>
      <span className="mb-5 grid h-14 w-14 place-items-center bg-brand text-signal-ink">
        <Mail size={26} aria-hidden="true" />
      </span>
      <h1 className="m-0 text-section text-ink">Kiểm tra hộp thư của bạn.</h1>
      <p className="mt-3 text-small leading-relaxed text-muted">
        Mã xác minh 6 số đã được gửi tới <strong className="text-ink">{email}</strong>. Mã có hiệu
        lực 10 phút.
      </p>

      <form className="mt-6 grid gap-4" onSubmit={submit}>
        <label className="grid gap-1.5">
          <span className="text-label text-muted">Mã xác minh</span>
          <input
            className="h-12 w-full rounded-[2px] border-2 border-rule-strong bg-surface px-3 text-center text-[1.5rem] tracking-[0.35em] text-ink outline-0 transition-colors focus:border-brand focus:shadow-[0_0_0_3px_var(--brand-soft)]"
            value={code}
            onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
            inputMode="numeric"
            autoComplete="one-time-code"
            placeholder="000000"
            maxLength={6}
            aria-label="Mã xác minh 6 số"
          />
        </label>
        <Button type="submit" pending={isSubmitting} disabled={code.length !== 6}>
          Xác minh email <ArrowRight size={16} />
        </Button>
        {notice && <DemoNotice tone={notice.tone}>{notice.message}</DemoNotice>}
      </form>

      <div className="mt-5 flex flex-wrap items-center justify-between gap-3">
        <button
          className="border-0 bg-transparent p-0 text-small font-semibold text-accent hover:text-ink disabled:cursor-not-allowed disabled:text-muted"
          type="button"
          onClick={resend}
          disabled={cooldown > 0 || isResending}
        >
          {isResending
            ? "Đang gửi lại…"
            : cooldown > 0
              ? `Gửi lại mã (${cooldown}s)`
              : "Gửi lại mã"}
        </button>
        <button
          className="border-0 bg-transparent p-0 text-small text-muted hover:text-ink"
          type="button"
          onClick={() => navigate("/register")}
        >
          Đổi email
        </button>
      </div>
    </div>
  );
}
