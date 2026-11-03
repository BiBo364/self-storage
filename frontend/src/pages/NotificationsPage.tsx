import { AlertTriangle, CheckCheck, Megaphone, Receipt, UserCheck } from "lucide-react";
import { useEffect, useState } from "react";
import { authGateway } from "../app/auth";
import {
  fetchNotificationPage,
  formatNotificationTime,
  markAllNotificationsRead,
  markNotificationRead,
  notificationGroup,
  notificationPath,
  useUnreadNotificationCount,
  type NotificationGroup,
  type NotificationSummary,
} from "../app/notifications";
import type { Navigate } from "../app/types";
import { NotificationDetailDialog } from "../components/domain/NotificationDetailDialog";
import { Button } from "../components/ui/Button";
import { DemoNotice } from "../components/ui/DemoNotice";
import { SurfaceState } from "../components/ui/SurfaceState";

const GROUP_ICON: Record<NotificationGroup, typeof AlertTriangle> = {
  approval: CheckCheck,
  invoice: Receipt,
  assignment: UserCheck,
  expiry: AlertTriangle,
  other: Megaphone,
};

const PAGE_SIZE = 20;

const GROUP_ICON_CLASS: Record<NotificationGroup, string> = {
  approval: "bg-success-soft text-success",
  invoice: "bg-warning-soft text-warning",
  assignment: "bg-info-soft text-info",
  expiry: "bg-danger-soft text-danger",
  other: "bg-surface-subtle text-muted",
};

export function NotificationsPage({ navigate }: { navigate: Navigate }) {
  const [items, setItems] = useState<NotificationSummary[] | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [detail, setDetail] = useState<NotificationSummary | null>(null);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [readFilter, setReadFilter] = useState<"all" | "unread">("all");
  const unreadCount = useUnreadNotificationCount();
  const session = authGateway.getSession();
  const role = session?.user.role ?? "CUSTOMER";

  useEffect(() => {
    let isActive = true;
    async function loadInitial() {
      try {
        const data = await fetchNotificationPage(0, PAGE_SIZE);
        if (isActive) {
          setItems(data);
          setHasMore(data.length === PAGE_SIZE);
        }
      } catch {
        if (isActive) setLoadError(true);
      } finally {
        if (isActive) setIsLoading(false);
      }
    }
    void loadInitial();
    return () => {
      isActive = false;
    };
  }, []);

  async function retry() {
    setIsLoading(true);
    setLoadError(false);
    try {
      const data = await fetchNotificationPage(
        0,
        PAGE_SIZE,
        readFilter === "unread" ? false : undefined,
      );
      setItems(data);
      setPage(0);
      setHasMore(data.length === PAGE_SIZE);
    } catch {
      setLoadError(true);
    } finally {
      setIsLoading(false);
    }
  }

  async function changeFilter(next: "all" | "unread") {
    if (next === readFilter) return;
    setReadFilter(next);
    setPage(0);
    setIsLoading(true);
    setLoadError(false);
    setActionError(null);
    try {
      const data = await fetchNotificationPage(0, PAGE_SIZE, next === "unread" ? false : undefined);
      setItems(data);
      setHasMore(data.length === PAGE_SIZE);
    } catch {
      setLoadError(true);
    } finally {
      setIsLoading(false);
    }
  }

  async function loadMore() {
    setIsLoadingMore(true);
    setActionError(null);
    try {
      const nextPage = page + 1;
      const data = await fetchNotificationPage(
        nextPage,
        PAGE_SIZE,
        readFilter === "unread" ? false : undefined,
      );
      setItems((current) => [...(current ?? []), ...data]);
      setPage(nextPage);
      setHasMore(data.length === PAGE_SIZE);
    } catch {
      setActionError("Không tải thêm được — thử lại sau.");
    } finally {
      setIsLoadingMore(false);
    }
  }

  const list = items ?? [];
  const unreadOnly = readFilter === "unread";

  async function openNotification(notification: NotificationSummary) {
    setActionError(null);
    try {
      await markNotificationRead(notification.id);
      setItems((current) => {
        if (!current) return current;
        if (unreadOnly) return current.filter((item) => item.id !== notification.id);
        return current.map((item) =>
          item.id === notification.id ? { ...item, readAt: new Date().toISOString() } : item,
        );
      });
    } catch {
      setActionError("Không đánh dấu đã đọc được — thử lại sau.");
    }
    setDetail(notification);
  }

  async function markAllRead() {
    setActionError(null);
    try {
      await markAllNotificationsRead();
      setItems((current) => {
        if (!current) return current;
        if (unreadOnly) return [];
        return current.map((item) =>
          item.readAt ? item : { ...item, readAt: new Date().toISOString() },
        );
      });
    } catch {
      setActionError("Không đánh dấu tất cả được — thử lại sau.");
    }
  }

  function tabClass(active: boolean) {
    return `border-0 px-2.5 py-1.5 text-small font-semibold ${
      active
        ? "bg-brand text-signal-ink"
        : "bg-transparent text-muted hover:bg-surface-subtle hover:text-ink"
    }`;
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Trung tâm thông báo</h1>
          <p className="mt-1.5 text-small text-muted">
            {isLoading
              ? "Đang tải thông báo…"
              : unreadCount > 0
                ? `${unreadCount} thông báo chưa đọc`
                : "Bạn đã đọc hết thông báo"}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex gap-1.5" role="group" aria-label="Lọc thông báo">
            <button
              className={tabClass(readFilter === "all")}
              aria-pressed={readFilter === "all"}
              onClick={() => void changeFilter("all")}
            >
              Tất cả
            </button>
            <button
              className={tabClass(readFilter === "unread")}
              aria-pressed={readFilter === "unread"}
              onClick={() => void changeFilter("unread")}
            >
              Chưa đọc
            </button>
          </div>
          <Button
            variant="secondary"
            size="sm"
            disabled={isLoading || unreadCount === 0}
            onClick={markAllRead}
          >
            <CheckCheck size={16} /> Đánh dấu đã đọc tất cả
          </Button>
        </div>
      </div>

      {actionError && <DemoNotice tone="error">{actionError}</DemoNotice>}

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải thông báo…" />
      ) : loadError ? (
        <SurfaceState
          variant="error"
          title="Không tải được thông báo"
          description="Kiểm tra kết nối tới máy chủ rồi thử lại."
          action={{ label: "Thử lại", onClick: () => void retry() }}
        />
      ) : list.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={unreadOnly ? "Không có thông báo chưa đọc" : "Chưa có thông báo nào"}
        />
      ) : (
        <>
          <div className="grid gap-2">
            {list.map((notification) => {
              const group = notificationGroup(notification.type);
              const Icon = GROUP_ICON[group];
              const isRead = !!notification.readAt;
              return (
                <button
                  key={notification.id}
                  className={`flex items-start gap-3 p-4 text-left transition-colors hover:bg-surface-subtle ${
                    isRead ? "bg-surface" : "bg-brand-soft"
                  }`}
                  onClick={() => void openNotification(notification)}
                >
                  <span
                    className={`grid h-9 w-9 shrink-0 place-items-center ${GROUP_ICON_CLASS[group]}`}
                  >
                    <Icon size={17} />
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-3">
                      <p
                        className={`m-0 text-small ${isRead ? "font-normal text-muted" : "font-semibold text-ink"}`}
                      >
                        {notification.title}
                      </p>
                      {!isRead && (
                        <span className="h-2 w-2 shrink-0 bg-brand" aria-label="Chưa đọc" />
                      )}
                    </div>
                    <p className="mt-1.5 text-label text-muted">
                      {formatNotificationTime(notification.createdAt)}
                    </p>
                  </div>
                </button>
              );
            })}
          </div>
          {hasMore && (
            <div className="flex justify-center">
              <Button
                variant="secondary"
                size="sm"
                pending={isLoadingMore}
                onClick={() => void loadMore()}
              >
                Xem thêm
              </Button>
            </div>
          )}
        </>
      )}

      {detail && (
        <NotificationDetailDialog
          notification={detail}
          path={notificationPath(detail, role)}
          onClose={() => setDetail(null)}
          onNavigate={(path) => {
            setDetail(null);
            navigate(path);
          }}
        />
      )}
    </div>
  );
}
