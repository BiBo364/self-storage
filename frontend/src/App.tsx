import { startTransition, useEffect, useState } from "react";
import { authGateway, type AuthSession, type UserRole } from "./app/auth";
import { AUTH_SESSION_EXPIRED_EVENT } from "./app/api";
import { AuthPage } from "./pages/auth/AuthPage";
import { BrowsePage } from "./pages/BrowsePage";
import { LandingPage } from "./pages/LandingPage";
import { RequestPage } from "./pages/RequestPage";
import { PublicFooter } from "./components/layout/PublicFooter";
import { PublicHeader } from "./components/layout/PublicHeader";
import { CustomerShell } from "./components/layout/CustomerShell";
import { FmShell } from "./components/layout/FmShell";
import { FsShell } from "./components/layout/FsShell";
import { MyStoragePage } from "./pages/MyStoragePage";
import { ContractDetailPage } from "./pages/ContractDetailPage";
import { ProposalsPage } from "./pages/ProposalsPage";
import { InvoicesPage } from "./pages/InvoicesPage";
import { AppointmentBookingPage } from "./pages/AppointmentBookingPage";
import { NotificationsPage } from "./pages/NotificationsPage";
import { RentalRequestQueuePage } from "./pages/fm/RentalRequestQueuePage";
import { RentalOrderListPage } from "./pages/fm/RentalOrderListPage";
import { ProposalRedoPage } from "./pages/fm/ProposalRedoPage";
import { AppointmentSchedulePage } from "./pages/fm/AppointmentSchedulePage";
import { ReturnRequestQueuePage } from "./pages/fm/ReturnRequestQueuePage";
import { ExtendRequestQueuePage } from "./pages/fm/ExtendRequestQueuePage";
import { SupportRequestQueuePage } from "./pages/fm/SupportRequestQueuePage";
import { StorageUnitManagementPage } from "./pages/fm/StorageUnitManagementPage";
import { StaffListPage } from "./pages/fm/StaffListPage";
import { FacilityReportPage } from "./pages/fm/FacilityReportPage";
import { FacilityInvoicesPage } from "./pages/fm/FacilityInvoicesPage";
import { CustomerContractOverviewPage } from "./pages/fm/CustomerContractOverviewPage";
import { DailySchedulePage } from "./pages/fs/DailySchedulePage";
import { HandoverChecklistPage } from "./pages/fs/HandoverChecklistPage";
import { HandoverQueuePage } from "./pages/fs/HandoverQueuePage";
import { ReturnQueuePage } from "./pages/fs/ReturnQueuePage";
import { ReturnCheckoutPage } from "./pages/fs/ReturnCheckoutPage";
import { StaffSupportPage } from "./pages/fs/SupportQueuePage";
import { HandoverPage } from "./pages/HandoverPage";
import { PaymentResultPage } from "./pages/PaymentResultPage";
import { SupportPage } from "./pages/SupportPage";
import { BomShell } from "./components/layout/BomShell";
import { AdminShell } from "./components/layout/AdminShell";
import { BomDashboardPage } from "./pages/bom/BomDashboardPage";
import { BomFacilitiesPage } from "./pages/bom/BomFacilitiesPage";
import { BomStaffPage } from "./pages/bom/BomStaffPage";
import { BomPricingPage } from "./pages/bom/BomPricingPage";
import { BomInvoicesPage } from "./pages/bom/BomInvoicesPage";
import { AdminAccountsPage } from "./pages/admin/AdminAccountsPage";
import { AdminPermissionsPage } from "./pages/admin/AdminPermissionsPage";
import { AdminLoginEventsPage } from "./pages/admin/AdminLoginEventsPage";
import { AdminAuditLogsPage } from "./pages/admin/AdminAuditLogsPage";
import type { Theme } from "./components/ui/ThemeToggle";
import { ROLE_HOME, viewFromLocation } from "./app/routes";
import { ChatWidget } from "./components/domain/ChatWidget";
import { View } from "./app/types";

const THEME_STORAGE_KEY = "kho-moc-theme";
const THEME_COLOR: Record<Theme, string> = { light: "#e9eae6", dark: "#0e1211" };

function getInitialTheme(): Theme {
  try {
    return window.localStorage.getItem(THEME_STORAGE_KEY) === "dark" ? "dark" : "light";
  } catch {
    return "light";
  }
}

function App() {
  const [view, setView] = useState<View>(viewFromLocation);
  // Giữ pathname để key lại trang đọc id từ URL — cùng view khác id thì setView không render lại.
  const [pathname, setPathname] = useState(() => window.location.pathname);
  const [theme, setTheme] = useState<Theme>(getInitialTheme);
  const [session, setSession] = useState<AuthSession | null>(null);
  const [isAuthLoading, setIsAuthLoading] = useState(true);

  useEffect(() => {
    document.documentElement.style.colorScheme = theme;
    document.documentElement.dataset.theme = theme;
    document.querySelector('meta[name="theme-color"]')?.setAttribute("content", THEME_COLOR[theme]);

    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, theme);
    } catch {
      // Theme still works for the current session when storage is unavailable.
    }
  }, [theme]);

  useEffect(() => {
    const onPopState = () => {
      setView(viewFromLocation());
      setPathname(window.location.pathname);
    };
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  useEffect(() => {
    function onSessionExpired() {
      authGateway.logout();
      window.history.replaceState({}, "", "/login");
      startTransition(() => setView(viewFromLocation()));
      window.scrollTo({ top: 0, behavior: "smooth" });
    }

    window.addEventListener(AUTH_SESSION_EXPIRED_EVENT, onSessionExpired);
    return () => window.removeEventListener(AUTH_SESSION_EXPIRED_EVENT, onSessionExpired);
  }, []);

  useEffect(() => {
    let isActive = true;
    const unsubscribe = authGateway.subscribe((nextSession) => {
      if (isActive) setSession(nextSession);
    });

    authGateway
      .restoreSession()
      .then((restoredSession) => {
        if (isActive) setSession(restoredSession);
      })
      .catch(() => {
        if (isActive) setSession(null);
      })
      .finally(() => {
        if (isActive) setIsAuthLoading(false);
      });

    return () => {
      isActive = false;
      unsubscribe();
    };
  }, []);

  function navigate(path: string) {
    window.history.pushState({}, "", path);
    startTransition(() => {
      setView(viewFromLocation());
      setPathname(window.location.pathname);
    });
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function toggleTheme() {
    setTheme((current) => (current === "dark" ? "light" : "dark"));
  }

  const isAuthView = view === "login" || view === "register" || view === "verify";
  const isCustomerView =
    view === "my-storage" ||
    view === "contract-detail" ||
    view === "proposals" ||
    view === "invoices" ||
    view === "appointments" ||
    view === "handover" ||
    view === "payment-result" ||
    view === "support" ||
    (view === "request" && !!session);
  const isFmView =
    view === "fm-rental-requests" ||
    view === "fm-orders" ||
    view === "fm-proposal-redo" ||
    view === "fm-appointments" ||
    view === "fm-return-requests" ||
    view === "fm-extend-requests" ||
    view === "fm-support-requests" ||
    view === "fm-storage-units" ||
    view === "fm-staff" ||
    view === "fm-reports" ||
    view === "fm-invoices" ||
    view === "fm-contracts";
  const isFsView =
    view === "fs-schedule" ||
    view === "fs-handover" ||
    view === "fs-handovers" ||
    view === "fs-returns" ||
    view === "fs-return-detail" ||
    view === "fs-support";
  const isBomView = view.startsWith("bom-");
  const isAdminView = view.startsWith("admin-");
  const isNotificationView = view === "notifications";
  // The route guard only picks the screen to render; the real gate is the API
  // (403 on /api/** for the wrong role). /notifications is shared by every
  // role per fe-pages/shared/03; today CUSTOMER, FM and FS have a shell.
  const allowedRoles: UserRole[] | null = isBomView
    ? ["BOM"]
    : isAdminView
      ? ["ADMIN"]
      : isFsView
        ? ["FS"]
        : isFmView
          ? ["FM"]
          : isCustomerView
            ? ["CUSTOMER"]
            : isNotificationView
              ? ["CUSTOMER", "FM", "FS"]
              : null;
  const needsLoginRedirect = !isAuthLoading && allowedRoles !== null && !session;
  const isWrongRole =
    !isAuthLoading &&
    allowedRoles !== null &&
    !!session &&
    !allowedRoles.includes(session.user.role);
  const roleHome = session ? ROLE_HOME[session.user.role] : undefined;
  const isCustomerShellView =
    isCustomerView || (isNotificationView && session?.user.role === "CUSTOMER");
  const isFmShellView = isFmView || (isNotificationView && session?.user.role === "FM");
  const isFsShellView = isFsView || (isNotificationView && session?.user.role === "FS");

  useEffect(() => {
    if (needsLoginRedirect) {
      navigate("/login");
      return;
    }
    if (isWrongRole && session) {
      // A role with its own home keeps the session and moves there; roles
      // without a screen yet (ADMIN/BOM) go back to the login page.
      if (roleHome) {
        navigate(roleHome);
      } else {
        authGateway.logout();
        navigate("/login");
      }
    }
  }, [isWrongRole, needsLoginRedirect, roleHome, session]);

  if (needsLoginRedirect || isWrongRole) {
    return null;
  }
  if (isAuthLoading) {
    return (
      <div className="grid min-h-screen place-items-center bg-background text-muted" role="status">
        Đang tải phiên đăng nhập…
      </div>
    );
  }
  return (
    <div className="app-shell" data-theme={theme}>
      <a className="skip-link" href="#main-content">
        Bỏ qua đến nội dung
      </a>
      {isAuthView ? (
        <AuthPage view={view} navigate={navigate} theme={theme} onThemeToggle={toggleTheme} />
      ) : isCustomerShellView ? (
        <CustomerShell view={view} navigate={navigate} theme={theme} onThemeToggle={toggleTheme}>
          {view === "my-storage" && <MyStoragePage navigate={navigate} />}
          {view === "contract-detail" && <ContractDetailPage navigate={navigate} />}
          {view === "proposals" && <ProposalsPage navigate={navigate} />}
          {view === "invoices" && <InvoicesPage navigate={navigate} />}
          {view === "appointments" && <AppointmentBookingPage navigate={navigate} />}
          {view === "notifications" && <NotificationsPage navigate={navigate} />}
          {view === "request" && <RequestPage navigate={navigate} embedded />}
          {view === "handover" && <HandoverPage navigate={navigate} />}
          {view === "payment-result" && <PaymentResultPage navigate={navigate} />}
          {view === "support" && <SupportPage navigate={navigate} />}
        </CustomerShell>
      ) : isFmShellView ? (
        <FmShell view={view} navigate={navigate} theme={theme} onThemeToggle={toggleTheme}>
          {view === "fm-rental-requests" && <RentalRequestQueuePage navigate={navigate} />}
          {view === "fm-orders" && <RentalOrderListPage navigate={navigate} />}
          {view === "fm-proposal-redo" && <ProposalRedoPage key={pathname} navigate={navigate} />}
          {view === "fm-appointments" && <AppointmentSchedulePage />}
          {view === "fm-return-requests" && <ReturnRequestQueuePage />}
          {view === "fm-extend-requests" && <ExtendRequestQueuePage />}
          {view === "fm-support-requests" && <SupportRequestQueuePage />}
          {view === "fm-storage-units" && <StorageUnitManagementPage navigate={navigate} />}
          {view === "fm-staff" && <StaffListPage />}
          {view === "fm-reports" && <FacilityReportPage navigate={navigate} />}
          {view === "fm-invoices" && <FacilityInvoicesPage />}
          {view === "fm-contracts" && <CustomerContractOverviewPage navigate={navigate} />}
          {isNotificationView && <NotificationsPage navigate={navigate} />}
        </FmShell>
      ) : isFsShellView ? (
        <FsShell view={view} navigate={navigate} theme={theme} onThemeToggle={toggleTheme}>
          {view === "fs-schedule" && <DailySchedulePage navigate={navigate} />}
          {view === "fs-handover" && <HandoverChecklistPage navigate={navigate} />}
          {view === "fs-handovers" && <HandoverQueuePage navigate={navigate} />}
          {view === "fs-returns" && <ReturnQueuePage navigate={navigate} />}
          {view === "fs-return-detail" && <ReturnCheckoutPage navigate={navigate} />}
          {view === "fs-support" && <StaffSupportPage navigate={navigate} />}
          {isNotificationView && <NotificationsPage navigate={navigate} />}
        </FsShell>
      ) : isBomView ? (
        <BomShell view={view} navigate={navigate} theme={theme} onThemeToggle={toggleTheme}>
          {view === "bom-dashboard" && <BomDashboardPage navigate={navigate} />}
          {view === "bom-facilities" && <BomFacilitiesPage navigate={navigate} />}
          {view === "bom-staff" && <BomStaffPage navigate={navigate} />}
          {view === "bom-pricing" && <BomPricingPage navigate={navigate} />}
          {view === "bom-invoices" && <BomInvoicesPage navigate={navigate} />}
        </BomShell>
      ) : isAdminView ? (
        <AdminShell view={view} navigate={navigate} theme={theme} onThemeToggle={toggleTheme}>
          {view === "admin-accounts" && <AdminAccountsPage navigate={navigate} />}
          {view === "admin-permissions" && <AdminPermissionsPage navigate={navigate} />}
          {view === "admin-login-events" && <AdminLoginEventsPage navigate={navigate} />}
          {view === "admin-audit-logs" && <AdminAuditLogsPage navigate={navigate} />}
        </AdminShell>
      ) : (
        <>
          <PublicHeader
            view={view}
            navigate={navigate}
            theme={theme}
            onThemeToggle={toggleTheme}
            session={session}
          />
          <main id="main-content">
            {view === "home" && <LandingPage navigate={navigate} />}
            {view === "units" && <BrowsePage navigate={navigate} />}
            {view === "request" && <RequestPage navigate={navigate} />}
          </main>
          <PublicFooter navigate={navigate} />
        </>
      )}
      {!isAuthView &&
        (isCustomerShellView ||
          (!isFmShellView && !isFsShellView && !isBomView && !isAdminView)) && <ChatWidget />}
    </div>
  );
}

export default App;
