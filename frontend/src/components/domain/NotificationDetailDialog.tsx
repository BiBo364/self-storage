import { useEffect, useId, useState } from "react";
import {
  fetchNotificationDetail,
  formatNotificationTime,
  type NotificationSummary,
} from "../../app/notifications";
import { Button } from "../ui/Button";
import { useModalA11y } from "../ui/useModalA11y";

/**
 * Dialog chi tiết notification (spec `fe-pages/shared/03`): danh sách chỉ có
 * title/thời gian, `body` đầy đủ lấy từ `GET /api/notifications/{id}` khi mở —
 * không fetch detail từng item. Có nút điều hướng khi `type`/`orderId` map được.
 */
export function NotificationDetailDialog({
  notification,
  path,
  onClose,
  onNavigate,
}: {
  notification: NotificationSummary;
  path?: string;
  onClose: () => void;
  onNavigate: (path: string) => void;
}) {
  const titleId = useId();
  const containerRef = useModalA11y<HTMLDivElement>(onClose);
  const [body, setBody] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    let isActive = true;
    async function loadInitial() {
      try {
        const detail = await fetchNotificationDetail(notification.id);
        if (isActive) setBody(detail.body);
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
  }, [notification.id]);

  async function retry() {
    setIsLoading(true);
    setLoadError(false);
    try {
      const detail = await fetchNotificationDetail(notification.id);
      setBody(detail.body);
    } catch {
      setLoadError(true);
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-[90] grid place-items-center bg-[rgba(7,16,19,0.68)] p-5"
      role="presentation"
      onClick={onClose}
    >
      <div
        ref={containerRef}
        tabIndex={-1}
        className="w-[min(560px,100%)] border border-[var(--container-border)] bg-surface p-6 shadow-[var(--shadow-soft)] focus:outline-none"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(event) => event.stopPropagation()}
      >
        <p className="mb-1 mt-0 text-label text-muted">
          {formatNotificationTime(notification.createdAt)}
        </p>
        <h2 id={titleId} className="mb-3 mt-0 text-section text-ink">
          {notification.title}
        </h2>
        {isLoading ? (
          <p className="m-0 text-small text-muted">Đang tải nội dung…</p>
        ) : loadError ? (
          <div className="flex flex-wrap items-center gap-3">
            <p className="m-0 text-small text-danger">Không tải được nội dung.</p>
            <Button variant="secondary" onClick={() => void retry()}>
              Thử lại
            </Button>
          </div>
        ) : (
          <p className="m-0 whitespace-pre-wrap text-small leading-relaxed text-ink">{body}</p>
        )}
        <div className="mt-6 flex justify-end gap-2.5">
          <Button variant="secondary" onClick={onClose}>
            Đóng
          </Button>
          {path && (
            <Button
              onClick={() => {
                onClose();
                onNavigate(path);
              }}
            >
              Đi tới
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
