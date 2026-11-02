import { useState } from "react";
import { ArrowRight, LogOut, Menu, X } from "lucide-react";
import { Navigate, View } from "../../app/types";
import { PAGE_CONTAINER } from "../../app/layout";
import { ROLE_HOME } from "../../app/routes";
import { authGateway, type AuthSession } from "../../app/auth";
import { Brand } from "../ui/Brand";
import { Button } from "../ui/Button";
import type { Theme } from "../ui/ThemeToggle";
import { ThemeToggle } from "../ui/ThemeToggle";

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

  function go(path: string) {
    setOpen(false);
    navigate(path);
  }
  function logout() {
    setOpen(false);
    authGateway.logout();
    navigate("/login");
  }
  const homePath = session ? (ROLE_HOME[session.user.role] ?? "/") : "/";
  const navLink =
    "border-0 bg-transparent p-0 text-left text-small font-semibold text-muted transition-colors duration-150 hover:text-ink max-[760px]:p-2.5";
  const activeLink =
    "border-0 bg-brand px-2 py-1 text-left text-small font-semibold text-signal-ink hover:text-signal-ink";

  return (
    <header className="sticky top-0 z-20 bg-surface">
      <div
        className={`${PAGE_CONTAINER} flex min-h-[74px] items-center justify-between gap-6 max-[760px]:min-h-[64px]`}
      >
        <Brand onClick={() => go("/")} />
        <button
          className="hidden h-9 w-9 place-items-center border-0 bg-transparent text-ink max-[760px]:grid"
          onClick={() => setOpen((current) => !current)}
          aria-label={open ? "Đóng menu" : "Mở menu"}
          aria-expanded={open}
        >
          {open ? <X size={21} /> : <Menu size={21} />}
        </button>
        <nav
          className={`flex items-center gap-6 max-[760px]:absolute max-[760px]:left-4 max-[760px]:right-4 max-[760px]:top-[64px] max-[760px]:flex-col max-[760px]:items-stretch max-[760px]:gap-1 max-[760px]:border max-[760px]:border-border max-[760px]:bg-surface max-[760px]:p-2 ${
            open ? "max-[760px]:flex" : "max-[760px]:hidden"
          }`}
          aria-label="Điều hướng chính"
        >
          <button className={view === "units" ? activeLink : navLink} onClick={() => go("/units")}>
            Tìm kho
          </button>
          <ThemeToggle theme={theme} onToggle={onThemeToggle} />
          {session ? (
            <>
              <Button className="max-[760px]:w-full" onClick={() => go(homePath)}>
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
              <Button className="max-[760px]:w-full" onClick={() => go("/rental-requests/new")}>
                Gửi nhu cầu <ArrowRight size={16} />
              </Button>
            </>
          )}
        </nav>
      </div>
    </header>
  );
}
