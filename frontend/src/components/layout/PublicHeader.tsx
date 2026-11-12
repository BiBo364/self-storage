import { useEffect, useState } from "react";
import { ArrowRight, LogOut, Menu, X } from "lucide-react";
import { Navigate, View } from "../../app/types";
import { PAGE_CONTAINER } from "../../app/layout";
import { ROLE_HOME } from "../../app/routes";
import { authGateway, type AuthSession } from "../../app/auth";
import { Brand } from "../ui/Brand";
import { Button } from "../ui/Button";
import type { Theme } from "../ui/ThemeToggle";
import { ThemeToggle } from "../ui/ThemeToggle";

/** Các phần của trang chủ — id khớp `<section id>` trong LandingPage. */
export const LANDING_SECTIONS = [
  { id: "about", label: "Giới thiệu" },
  { id: "locations", label: "Cơ sở" },
  { id: "sizes", label: "Loại khoang" },
  { id: "how-it-works", label: "Quy trình" },
];

/** Phần trang chủ đang xem: phần cuối cùng có đỉnh đã cuộn qua dưới header (chỉ theo dõi khi ở trang chủ). */
function useActiveSection(enabled: boolean) {
  const [active, setActive] = useState<string | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let frame = 0;
    const update = () => {
      frame = 0;
      let current: string | null = null;
      for (const section of LANDING_SECTIONS) {
        const element = document.getElementById(section.id);
        if (element && element.getBoundingClientRect().top <= 140) current = section.id;
      }
      setActive(current);
    };
    const onScroll = () => {
      if (!frame) frame = window.requestAnimationFrame(update);
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
    return () => {
      window.removeEventListener("scroll", onScroll);
      if (frame) window.cancelAnimationFrame(frame);
    };
  }, [enabled]);
  return enabled ? active : null;
}

export function PublicHeader({
  view,
  navigate,
  theme,
  onThemeToggle,
  session,
}: {
  view: View;
  navigate: Navigate;
  theme: Theme;
  onThemeToggle: () => void;
  session: AuthSession | null;
}) {
  const [open, setOpen] = useState(false);
  const onHome = view === "home";
  const activeSection = useActiveSection(onHome);

  function go(path: string) {
    setOpen(false);
    navigate(path);
  }
  function jump(id: string) {
    setOpen(false);
    if (onHome) {
      document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
      window.history.replaceState({}, "", `/#${id}`);
    } else {
      navigate(`/#${id}`);
    }
  }
  function logout() {
    setOpen(false);
    authGateway.logout();
    navigate("/login");
  }
  const homePath = session ? (ROLE_HOME[session.user.role] ?? "/") : "/";
  const navLink =
    "border-0 bg-transparent p-0 text-left text-small font-semibold text-muted transition-colors duration-150 hover:text-ink max-[1180px]:p-2.5";
  const activeLink =
    "border-0 bg-brand px-2.5 py-1 text-left text-small font-semibold text-signal-ink hover:text-signal-ink";
  // Gạch chân vàng chạy ra khi rê chuột / khi đang xem phần đó.
  const sectionLink = (current: boolean) =>
    `relative border-0 bg-transparent p-0 text-left text-small font-semibold transition-colors duration-150 after:absolute after:-bottom-1.5 after:left-0 after:h-[2px] after:w-full after:origin-left after:rounded-full after:bg-brand after:transition-transform after:duration-300 after:content-[''] hover:text-ink hover:after:scale-x-100 max-[1180px]:p-2.5 max-[1180px]:after:hidden ${
      current ? "text-ink after:scale-x-100" : "text-muted after:scale-x-0"
    }`;

  const sectionButtons = LANDING_SECTIONS.map((section) => (
    <button
      key={section.id}
      className={sectionLink(activeSection === section.id)}
      aria-current={activeSection === section.id ? "true" : undefined}
      onClick={() => jump(section.id)}
    >
      {section.label}
    </button>
  ));

  return (
    <header className="sticky top-0 z-20 rounded-none bg-surface">
      {/* 3 cột: logo trái · các phần trang chủ ở giữa · thao tác bên phải. Dưới 1180px gom vào menu. */}
      <div
        className={`${PAGE_CONTAINER} grid min-h-[74px] grid-cols-[1fr_auto_1fr] items-center gap-6 max-[1180px]:flex max-[1180px]:justify-between max-[760px]:min-h-[64px]`}
      >
        <div className="justify-self-start">
          <Brand onClick={() => go("/")} />
        </div>
        <div
          className="flex items-center gap-7 max-[1180px]:hidden"
          aria-label="Các phần của trang chủ"
          role="group"
        >
          {sectionButtons}
        </div>
        <div className="flex items-center justify-end">
          <button
            className="hidden h-9 w-9 place-items-center border-0 bg-transparent text-ink max-[1180px]:grid"
            onClick={() => setOpen((current) => !current)}
            aria-label={open ? "Đóng menu" : "Mở menu"}
            aria-expanded={open}
          >
            {open ? <X size={21} /> : <Menu size={21} />}
          </button>
          <nav
            className={`flex items-center gap-6 whitespace-nowrap max-[1180px]:absolute max-[1180px]:left-4 max-[1180px]:right-4 max-[1180px]:top-[74px] max-[1180px]:flex-col max-[1180px]:items-stretch max-[1180px]:gap-1 max-[1180px]:border max-[1180px]:border-border max-[1180px]:bg-surface max-[1180px]:p-2 max-[1180px]:shadow-[var(--shadow-soft)] max-[760px]:top-[64px] ${
              open ? "max-[1180px]:flex" : "max-[1180px]:hidden"
            }`}
            aria-label="Điều hướng chính"
          >
            <div className="hidden flex-col items-stretch gap-1 max-[1180px]:flex">
              {sectionButtons}
            </div>
            <button
              className={view === "units" ? activeLink : navLink}
              onClick={() => go("/units")}
            >
              Tìm kho
            </button>
            <ThemeToggle theme={theme} onToggle={onThemeToggle} />
            {session ? (
              <>
                <Button className="max-[1180px]:w-full" onClick={() => go(homePath)}>
                  Không gian của bạn <ArrowRight size={16} />
                </Button>
                <button className={`${navLink} inline-flex items-center gap-1.5`} onClick={logout}>
                  <LogOut size={15} /> Đăng xuất
                </button>
              </>
            ) : (
              <>
                <button className={navLink} onClick={() => go("/login")}>
                  Đăng nhập
                </button>
                <Button className="max-[1180px]:w-full" onClick={() => go("/rental-requests/new")}>
                  Gửi nhu cầu <ArrowRight size={16} />
                </Button>
              </>
            )}
          </nav>
        </div>
      </div>
    </header>
  );
}
