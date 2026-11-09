import {
  Bell,
  CalendarDays,
  CalendarRange,
  ClipboardCheck,
  ClipboardPen,
  LifeBuoy,
  Undo2,
} from "lucide-react";
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
  {
    label: "Đơn đang bàn giao",
    path: "/staff/handovers",
    view: "fs-handovers",
    icon: ClipboardCheck,
  },
  { label: "Trả kho", path: "/staff/returns", view: "fs-returns", icon: Undo2 },
  { label: "Yêu cầu hỗ trợ", path: "/staff/support", view: "fs-support", icon: LifeBuoy },
  { label: "Ca của tôi", path: "/staff/shifts", view: "fs-shifts", icon: CalendarRange },
  { label: "Nghỉ phép & hồ sơ", path: "/staff/leaves", view: "fs-leaves", icon: ClipboardPen },
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
  const activeView =
    view === "fs-handover" ? "fs-handovers" : view === "fs-return-detail" ? "fs-returns" : view;
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
