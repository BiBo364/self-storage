import { Navigate } from "../../app/types";
import { PAGE_CONTAINER } from "../../app/layout";
import { Brand } from "../ui/Brand";

const footerLink =
  "w-fit border-0 bg-transparent p-0 text-left text-small text-muted hover:text-ink";

export function PublicFooter({ navigate }: { navigate: Navigate }) {
  return (
    <footer className="bg-[var(--footer-surface)] text-ink">
      <div className={`${PAGE_CONTAINER} pb-10 pt-12 max-[760px]:pt-10`}>
        <div className="grid grid-cols-[1.5fr_repeat(3,0.65fr)] gap-10 max-[980px]:gap-8 max-[760px]:grid-cols-2">
          <div className="max-[760px]:col-span-2">
            <Brand onClick={() => navigate("/")} />
            <p className="mt-3 max-w-[32ch] text-small text-muted">
              Tự lưu trữ tại TP. Hồ Chí Minh. Prototype — dữ liệu minh hoạ.
            </p>
          </div>
          <div className="grid content-start gap-2.5">
            <strong className="mb-1 text-label text-muted">Sản phẩm</strong>
            <button className={footerLink} onClick={() => navigate("/units")}>
              Tìm kho
            </button>
            <a className={footerLink} href="/#how-it-works">
              Quy trình
            </a>
            <a className={footerLink} href="/#locations">
              Cơ sở
            </a>
          </div>
          <div className="grid content-start gap-2.5">
            <strong className="mb-1 text-label text-muted">Hỗ trợ</strong>
            <a className={footerLink} href="mailto:hello@khomoc.example">
              Email cho Kho Mộc
            </a>
            <a className={footerLink} href="tel:+842812345678">
              028 1234 5678
            </a>
            <button className={footerLink} onClick={() => navigate("/rental-requests/new")}>
              Gửi nhu cầu
            </button>
          </div>
          <div className="grid content-start gap-2.5">
            <strong className="mb-1 text-label text-muted">Pháp lý</strong>
            <span className="text-small text-muted">Điều khoản sử dụng</span>
            <span className="text-small text-muted">Quyền riêng tư</span>
          </div>
        </div>
        <div className="mt-10 flex items-center justify-between gap-4 text-label text-muted max-[760px]:block">
          <span>© 2026 Kho Mộc. Bản xem trước giao diện.</span>
          <span className="max-[760px]:mt-2 max-[760px]:block">Dữ liệu minh hoạ</span>
        </div>
      </div>
    </footer>
  );
}
