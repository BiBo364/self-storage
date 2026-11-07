import { LogIn, ScrollText, ShieldCheck, Users } from "lucide-react";
import { authGateway } from "../../app/auth";
import type { Navigate, View } from "../../app/types";
import type { Theme } from "../ui/ThemeToggle";
import { WorkspaceShell, type WorkspaceNavItem } from "./WorkspaceShell";

const navigation: (WorkspaceNavItem & { view: View })[] = [
  { label: "Tài khoản", path: "/admin/accounts", view: "admin-accounts", icon: Users },
  { label: "Phân quyền", path: "/admin/permissions", view: "admin-permissions", icon: ShieldCheck },
  {
    label: "Lịch sử đăng nhập",
    path: "/admin/login-events",
    view: "admin-login-events",
    icon: LogIn,
  },
  {
    label: "Nhật ký thao tác",
    path: "/admin/audit-logs",
    view: "admin-audit-logs",
    icon: ScrollText,
  },
];

export function AdminShell({
  view,
  navigate,
  theme,
  onThemeToggle,
  children,
}: {
  view: View;
  navigate: Navigate;
  theme: Theme;
  onThemeToggle: () => void;
  children: React.ReactNode;
}) {
  const session = authGateway.getSession();
  const activeItem = navigation.find((item) => item.view === view);

  function logout() {
    authGateway.logout();
    navigate("/login");
  }

  return (
    <WorkspaceShell
      roleLabel="System Administrator"
      navItems={navigation}
      activePath={activeItem?.path ?? ""}
      onNavigate={(path) => navigate(path)}
      onBrandClick={() => navigate("/admin/accounts")}
      user={{ name: session?.user.name ?? "", email: session?.user.email ?? "" }}
      onLogout={logout}
      theme={theme}
      onThemeToggle={onThemeToggle}
    >
      {children}
    </WorkspaceShell>
  );
}
