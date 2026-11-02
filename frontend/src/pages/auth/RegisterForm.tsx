import { FormEvent, useState } from "react";
import { ArrowRight, LockKeyhole, Mail } from "lucide-react";
import { Navigate, Notice } from "../../app/types";
import { authApi } from "../../app/auth";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { FormField } from "../../components/ui/FormField";

export function RegisterForm({ navigate }: { navigate: Navigate }) {
  const [notice, setNotice] = useState<Notice>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const email = String(data.get("email") ?? "").trim();
    const password = String(data.get("password") ?? "");
    const confirmPassword = String(data.get("confirmPassword") ?? "");

    if (!email || !password) {
      setNotice({ tone: "error", message: "Vui lòng nhập email và mật khẩu." });
      return;
    }
    if (password.length < 8) {
      setNotice({ tone: "error", message: "Mật khẩu cần tối thiểu 8 ký tự." });
      return;
    }
    if (password !== confirmPassword) {
      setNotice({ tone: "error", message: "Mật khẩu nhập lại chưa khớp." });
      return;
    }
    if (!data.get("terms")) {
      setNotice({ tone: "error", message: "Bạn cần đồng ý điều khoản sử dụng." });
      return;
    }

    setIsSubmitting(true);
    const result = await authApi.register(email, password);
    setIsSubmitting(false);
    if (!result.ok) {
      setNotice({
        tone: "error",
        message:
          result.status === 409
            ? "Email này đã được đăng ký. Thử đăng nhập hoặc dùng email khác."
            : result.message,
      });
      return;
    }
    navigate(`/verify-email?email=${encodeURIComponent(email)}`);
  }

  return (
    <>
      <div className="mb-6">
        <h1 className="m-0 text-section text-ink">Tạo tài khoản</h1>
        <p className="mt-2 text-small text-muted">
          Đăng ký Customer để theo dõi nhu cầu lưu trữ, hợp đồng và hoá đơn.
        </p>
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
        <FormField
          label="Mật khẩu"
          name="password"
          type="password"
          required
          placeholder="Tối thiểu 8 ký tự"
          icon={<LockKeyhole size={17} />}
        />
        <FormField
          label="Nhập lại mật khẩu"
          name="confirmPassword"
          type="password"
          required
          placeholder="Nhập lại mật khẩu"
          icon={<LockKeyhole size={17} />}
        />
        <label className="mb-4 flex items-start gap-2 text-small text-muted">
          <input className="mt-0.5 h-4 w-4 accent-brand" type="checkbox" name="terms" required />
          <span>Tôi đồng ý với điều khoản sử dụng của Kho Mộc.</span>
        </label>
        <Button type="submit" pending={isSubmitting} className="w-full">
          Tạo tài khoản <ArrowRight size={17} />
        </Button>
        {notice && <DemoNotice tone={notice.tone}>{notice.message}</DemoNotice>}
      </form>
      <p className="mt-5 text-center text-small text-muted">
        Đã có tài khoản?{" "}
        <button
          className="border-0 bg-transparent p-0 font-semibold text-accent hover:text-ink"
          onClick={() => navigate("/login")}
        >
          Đăng nhập
        </button>
      </p>
    </>
  );
}
