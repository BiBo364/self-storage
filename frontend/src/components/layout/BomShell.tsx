import { BarChart3, Building2, Receipt, SlidersHorizontal, Users } from "lucide-react";
import { authGateway } from "../../app/auth";
import type { Navigate, View } from "../../app/types";
import type { Theme } from "../ui/ThemeToggle";
import { WorkspaceShell, type WorkspaceNavItem } from "./WorkspaceShell";

const navigation: (WorkspaceNavItem & { view: View })[] = [
  { label: "Tổng quan doanh thu", path: "/bom/dashboard", view: "bom-dashboard", icon: BarChart3 },
  { label: "Cơ sở & FM", path: "/bom/facilities", view: "bom-facilities", icon: Building2 },
  { label: "Nhân viên FS", path: "/bom/staff", view: "bom-staff", icon: Users },
  { label: "Chính sách & giá", path: "/bom/pricing", view: "bom-pricing", icon: SlidersHorizontal },
  { label: "Hoá đơn toàn chuỗi", path: "/bom/invoices", view: "bom-invoices", icon: Receipt },
];

export function BomShell({
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
      roleLabel="Business Operation Manager"
      navItems={navigation}
      activePath={activeItem?.path ?? ""}
      onNavigate={(path) => navigate(path)}
      onBrandClick={() => navigate("/bom/dashboard")}
      user={{ name: session?.user.name ?? "", email: session?.user.email ?? "" }}
      onLogout={logout}
      theme={theme}
      onThemeToggle={onThemeToggle}
    >
      {children}
    </WorkspaceShell>
  );
}
