import { ChevronDown, LogOut, Menu, X } from "lucide-react";
import type { ComponentType, ReactNode } from "react";
import { useEffect, useRef, useState } from "react";
import { NotificationBell } from "../domain/NotificationBell";
import { Brand } from "../ui/Brand";
import type { Theme } from "../ui/ThemeToggle";
import { ThemeToggle } from "../ui/ThemeToggle";

export type WorkspaceNavItem = {
  label: string;
  path: string;
  icon: ComponentType<{ size?: number }>;
  badge?: number;
};

const SIDEBAR_ICON_BUTTON =
  "hidden h-9 w-9 place-items-center border-0 bg-transparent text-ink max-[760px]:grid";

export function WorkspaceShell({
  roleLabel,
  navItems,
  activePath,
  onNavigate,
  onBrandClick,
  user,
  onLogout,
  theme,
  onThemeToggle,
  sidebarNote,
  header,
  contentClassName,
  children,
}: {
  roleLabel: string;
  navItems: WorkspaceNavItem[];
  activePath: string;
  onNavigate: (path: string) => void;
  onBrandClick: () => void;
  user?: { name: string; email: string };
  onLogout: () => void;
  theme?: Theme;
  onThemeToggle?: () => void;
  /** Extra content pinned above the logout button (e.g. a demo/env banner). */
  sidebarNote?: ReactNode;
  /** Override the default topbar (theme toggle + profile menu) — receives a
   * callback to open the mobile sidebar, since that state lives in here. */
  header?: (openMobileMenu: () => void) => ReactNode;
  /** Override the content wrapper's own classes (width/padding), when a
   * role's header height/shape needs different clearance than the default. */
  contentClassName?: string;
  children: ReactNode;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const profileRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handlePointerDown(event: PointerEvent) {
      if (profileRef.current && !profileRef.current.contains(event.target as Node)) {
        setProfileOpen(false);
      }
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") setProfileOpen(false);
    }
    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, []);

  return (
    <div className="flex min-h-screen bg-background">
      <aside
        className={`fixed inset-y-0 left-0 z-20 flex w-[236px] flex-col bg-[var(--dark)] px-3 py-4 text-[var(--on-dark-ink)] transition-transform duration-200 max-[760px]:z-30 max-[760px]:w-[min(280px,88vw)] max-[760px]:-translate-x-full ${
          menuOpen ? "max-[760px]:translate-x-0" : ""
        }`}
      >
        <div className="flex items-center justify-between px-2 pb-6">
          <Brand tone="onDark" onClick={onBrandClick} />
          <button
            className="hidden h-8 w-8 place-items-center border-0 bg-transparent text-[var(--on-dark-ink)] max-[760px]:grid"
            onClick={() => setMenuOpen(false)}
            aria-label="Đóng menu"
          >
            <X size={18} />
          </button>
        </div>
        <div className="mb-2 px-2.5 text-label text-[rgba(233,234,230,.55)]">{roleLabel}</div>
        <nav className="grid gap-0.5" aria-label={`Điều hướng ${roleLabel}`}>
          {navItems.map(({ label, path, icon: Icon, badge }) => {
            const isActive = activePath === path;
            return (
              <button
                key={path}
                className={`flex w-full items-center gap-2.5 border-0 px-2.5 py-2.5 text-left font-display text-small font-medium transition-colors duration-150 ${
                  isActive
                    ? "bg-brand text-signal-ink"
                    : "bg-transparent text-[rgba(233,234,230,.72)] hover:bg-[rgba(233,234,230,.08)] hover:text-[var(--on-dark-ink)]"
                }`}
                onClick={() => {
                  setMenuOpen(false);
                  onNavigate(path);
                }}
              >
                <Icon size={17} />
                <span>{label}</span>
                {typeof badge === "number" && badge > 0 && (
                  <span
                    className={`ml-auto grid min-w-5 place-items-center px-1.5 text-label font-semibold ${
                      isActive ? "bg-signal-ink text-brand" : "bg-brand text-signal-ink"
                    }`}
                  >
                    {badge}
                  </span>
                )}
              </button>
            );
          })}
        </nav>
        <div className="mt-auto grid gap-3 px-2.5 pt-4">
          {sidebarNote}
          <button
            className="inline-flex items-center gap-2 border-0 bg-transparent px-0 py-2 text-left text-small font-semibold text-[rgba(233,234,230,.65)] hover:text-[var(--danger)]"
            onClick={onLogout}
          >
            <LogOut size={16} /> Đăng xuất
          </button>
        </div>
      </aside>
      {menuOpen && (
        <button
          className="fixed inset-0 z-20 block border-0 bg-[rgba(0,0,0,0.52)]"
          onClick={() => setMenuOpen(false)}
          aria-label="Đóng menu"
        />
      )}
      <section className="min-w-0 flex-1 ml-[236px] max-[760px]:ml-0">
        {header ? (
          header(() => setMenuOpen(true))
        ) : (
          <header className="sticky top-0 z-[15] flex min-h-16 items-center justify-between bg-surface px-5 max-[760px]:px-4">
            <button
              className={SIDEBAR_ICON_BUTTON}
              onClick={() => setMenuOpen(true)}
              aria-label="Mở menu"
            >
              <Menu size={20} />
            </button>
            <div className="ml-auto flex items-center gap-4 max-[760px]:gap-3">
              <NotificationBell onNavigate={onNavigate} />
              {theme && onThemeToggle && <ThemeToggle theme={theme} onToggle={onThemeToggle} />}
              <div className="relative" ref={profileRef}>
                <button
                  className="flex items-center gap-2.5 bg-transparent p-0 text-left text-ink"
                  onClick={() => setProfileOpen((current) => !current)}
                  aria-expanded={profileOpen}
                  aria-haspopup="menu"
                >
                  <span className="grid h-8 w-8 place-items-center bg-brand text-small font-semibold text-signal-ink">
                    {user?.name.slice(0, 1)}
                  </span>
                  <span className="grid gap-px max-[760px]:hidden">
                    <strong className="block max-w-[180px] truncate text-small">
                      {user?.name}
                    </strong>
                    <small className="block text-label text-muted">{roleLabel}</small>
                  </span>
                  <ChevronDown className="max-[760px]:hidden" size={16} />
                </button>
                {profileOpen && (
                  <div
                    className="absolute right-0 top-[calc(100%+10px)] z-40 w-[230px] border border-border bg-surface p-2 shadow-[var(--shadow-soft)]"
                    role="menu"
                  >
                    <div className="grid gap-0.5 p-2.5">
                      <strong className="block max-w-[180px] truncate text-small">
                        {user?.name}
                      </strong>
                      <span className="overflow-hidden text-ellipsis whitespace-nowrap text-label text-muted">
                        {user?.email}
                      </span>
                    </div>
                    <button
                      className="flex w-full items-center gap-2 border-0 bg-transparent px-2.5 py-2.5 text-left text-small font-semibold text-danger hover:bg-brand-soft"
                      role="menuitem"
                      onClick={onLogout}
                    >
                      <LogOut size={16} /> Đăng xuất
                    </button>
                  </div>
                )}
              </div>
            </div>
          </header>
        )}
        <div
          className={
            contentClassName ??
            "mx-auto w-[min(1320px,calc(100%-64px))] pb-16 pt-8 max-[760px]:w-[min(100%-32px,600px)] max-[760px]:pb-12 max-[760px]:pt-6"
          }
          id="main-content"
        >
          {children}
        </div>
      </section>
    </div>
  );
}
