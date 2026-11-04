import { Bell, CalendarDays, PackageOpen, Receipt } from "lucide-react";
import { authGateway } from "../../app/auth";
import { useUnreadNotificationCount } from "../../app/notifications";
import type { Navigate, View } from "../../app/types";
import type { Theme } from "../ui/ThemeToggle";
import { WorkspaceShell, type WorkspaceNavItem } from "./WorkspaceShell";

const navigation: (WorkspaceNavItem & { view: View })[] = [
  {
    label: "Kho của tôi",
    path: "/my-storage",
    view: "my-storage",
    icon: PackageOpen,
  },
  {
    label: "Hoá đơn & thanh toán",
    path: "/invoices",
    view: "invoices",
    icon: Receipt,
  },
  {
    label: "Lịch hẹn",
    path: "/appointments/new",
    view: "appointments",
    icon: CalendarDays,
  },
  {
    label: "Thông báo",
    path: "/notifications",
    view: "notifications",
    icon: Bell,
  },
];

export function CustomerShell({
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
  const unreadNotifications = useUnreadNotificationCount();
  const activeView = view === "contract-detail" ? "my-storage" : view;
  const activeItem = navigation.find((item) => item.view === activeView);

  const navItems = navigation.map((item) =>
    item.path === "/notifications" ? { ...item, badge: unreadNotifications } : item,
  );

  function logout() {
    authGateway.logout();
    navigate("/login");
  }

  return (
    <WorkspaceShell
      roleLabel="Không gian của bạn"
      navItems={navItems}
      activePath={activeItem?.path ?? ""}
      onNavigate={navigate}
      onBrandClick={() => navigate("/")}
      user={{ name: session?.user.name ?? "", email: session?.user.email ?? "" }}
      onLogout={logout}
      theme={theme}
      onThemeToggle={onThemeToggle}
    >
      {children}
    </WorkspaceShell>
  );
}
