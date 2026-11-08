import { ClipboardCheck } from "lucide-react";
import { authGateway } from "../../app/auth";
import type { Navigate, View } from "../../app/types";
import type { Theme } from "../ui/ThemeToggle";
import { WorkspaceShell, type WorkspaceNavItem } from "./WorkspaceShell";

const navigation: (WorkspaceNavItem & { view: View })[] = [
  {
    label: "Đối soát thanh toán",
    path: "/admin/reconciliation",
    view: "admin-payments",
    icon: ClipboardCheck,
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
      roleLabel="Admin"
      navItems={navigation}
      activePath={activeItem?.path ?? ""}
      onNavigate={navigate}
      onBrandClick={() => navigate("/admin/reconciliation")}
      user={{ name: session?.user.name ?? "", email: session?.user.email ?? "" }}
      onLogout={logout}
      theme={theme}
      onThemeToggle={onThemeToggle}
    >
      {children}
    </WorkspaceShell>
  );
}
