import { ChevronRight, Clock3, MapPin } from "lucide-react";
import { useEffect, useState } from "react";
import { formatDate, formatTime } from "../../app/format";
import { errorMessage, errorStatus } from "../../app/http";
import { fetchStaffHandovers, handoverStep, type HandoverView } from "../../app/services/staff";
import type { Navigate } from "../../app/types";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

/** FS — đơn đang bàn giao. */
export function HandoverQueuePage({ navigate }: { navigate: Navigate }) {
  const [handovers, setHandovers] = useState<HandoverView[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const list = await fetchStaffHandovers();
        if (active) setHandovers(list);
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [reloadKey]);

  function reload() {
    setIsLoading(true);
    setError(null);
    setReloadKey((key) => key + 1);
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Đơn đang bàn giao</h1>
        <p className="mt-1.5 text-small text-muted">
          Phiên bàn giao đang mở của các lịch được phân cho bạn, sau khi khách đã đến.
        </p>
      </div>

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải phiên bàn giao…" />
      ) : error ? (
        error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xem phiên bàn giao"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được phiên bàn giao"
            description={error.message}
            action={{ label: "Thử lại", onClick: reload }}
          />
        )
      ) : handovers.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Chưa có phiên bàn giao nào đang mở"
          description="Phiên xuất hiện ở đây sau khi bạn bấm “Khách đã đến” trên lịch làm việc."
          action={{ label: "Mở lịch làm việc", onClick: () => navigate("/staff/schedule") }}
        />
      ) : (
        <div className="grid gap-2">
          {handovers.map((handover) => {
            const step = handoverStep(handover.step);
            return (
              <button
                key={handover.id}
                className="flex flex-wrap items-center justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
                onClick={() =>
                  navigate(
                    `/staff/appointments/${handover.appointmentId}/handover?handover=${handover.id}`,
                  )
                }
              >
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <StatusTag tone={step.tone}>{step.label}</StatusTag>
                    <span className="text-label text-muted">Đơn {handover.orderId}</span>
                  </div>
                  <strong className="mt-2 block text-section text-ink">
                    {handover.customerName ?? handover.customerEmail ?? "—"}
                  </strong>
                  <span className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-small text-muted">
                    <span className="flex items-center gap-1.5">
                      <MapPin size={14} /> {handover.unitCode ?? "—"}
                      {handover.unitType ? ` · ${handover.unitType}` : ""}
                    </span>
                    <span className="flex items-center gap-1.5">
                      <Clock3 size={14} /> Đến lúc {formatTime(handover.arrivedAt)}{" "}
                      {formatDate(handover.arrivedAt)}
                    </span>
                    {handover.dueAt && <span>Hạn bàn giao {formatDate(handover.dueAt)}</span>}
                  </span>
                </div>
                <ChevronRight size={18} className="text-muted" />
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
