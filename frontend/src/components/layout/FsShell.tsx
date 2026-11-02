import { Bell, CalendarDays } from "lucide-react";
import { authGateway } from "../../app/auth";
import { useUnreadNotificationCount } from "../../app/notifications";
import type { Navigate, View } from "../../app/types";
import type { Theme } from "../ui/ThemeToggle";
import { WorkspaceShell, type WorkspaceNavItem } from "./WorkspaceShell";

const navigation: (WorkspaceNavItem & { view: View })[] = [
  {
    label: "Lịch làm việc",
    path: "/staff/schedule",
    view: "fs-schedule",
    icon: CalendarDays,
  },
  { label: "Thông báo", path: "/notifications", view: "notifications", icon: Bell },
];

export function FsShell({
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
  // Checklist bàn giao là trang chi tiết của lịch làm việc — highlight mục cha.
  const activeView = view === "fs-handover" ? "fs-schedule" : view;
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
      roleLabel="Facility Staff"
      navItems={navItems}
      activePath={activeItem?.path ?? ""}
      onNavigate={(path) => navigate(path)}
      onBrandClick={() => navigate("/staff/schedule")}
      user={{ name: session?.user.name ?? "", email: session?.user.email ?? "" }}
      onLogout={logout}
      theme={theme}
      onThemeToggle={onThemeToggle}
    >
      {children}
    </WorkspaceShell>
  );
}
