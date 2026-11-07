import { CalendarDays, ChevronRight, MapPin } from "lucide-react";
import { useEffect, useState } from "react";
import { formatDate, formatDateTime } from "../../app/format";
import { errorMessage, errorStatus } from "../../app/http";
import {
  arriveAppointment,
  fetchStaffReturnRequests,
  type ReturnRequestView,
} from "../../app/services/staff";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { SurfaceState } from "../../components/ui/SurfaceState";

/** FS — buổi trả kho được phân công. */
export function ReturnQueuePage({ navigate }: { navigate: Navigate }) {
  const [requests, setRequests] = useState<ReturnRequestView[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [arrivingId, setArrivingId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const list = await fetchStaffReturnRequests();
        if (active) setRequests(list);
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

  async function arrive(request: ReturnRequestView) {
    if (request.appointmentId === null) return;
    setArrivingId(request.id);
    setActionError(null);
    setNotice(null);
    try {
      await arriveAppointment(request.appointmentId);
      setNotice(`Đã xác nhận khách đến trả khoang ${request.unitCode ?? ""}.`);
    } catch (caught) {
      setActionError(errorMessage(caught));
    } finally {
      setArrivingId(null);
    }
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Trả kho</h1>
        <p className="mt-1.5 text-small text-muted">
          Buổi trả kho FM đã phân cho bạn. Xác nhận khách đến rồi mở biên bản để kiểm tra khoang.
        </p>
      </div>

      {actionError && <DemoNotice tone="error">{actionError}</DemoNotice>}
      {notice && !actionError && <DemoNotice tone="success">{notice}</DemoNotice>}

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải buổi trả kho…" />
      ) : error ? (
        error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xem buổi trả kho"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được buổi trả kho"
            description={error.message}
            action={{ label: "Thử lại", onClick: reload }}
          />
        )
      ) : requests.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Chưa có buổi trả kho nào được phân cho bạn"
          description="Khi FM phân công một yêu cầu trả kho, buổi hẹn sẽ hiện ở đây."
          action={{ label: "Mở lịch làm việc", onClick: () => navigate("/staff/schedule") }}
        />
      ) : (
        <div className="grid gap-2">
          {requests.map((request) => (
            <article
              key={request.id}
              className="flex flex-wrap items-center justify-between gap-4 bg-surface p-4"
            >
              <div className="min-w-0">
                <span className="text-label text-muted">Yêu cầu trả kho #{request.id}</span>
                <strong className="mt-1 flex items-center gap-1.5 text-section text-ink">
                  <MapPin size={16} /> {request.unitCode ?? "—"}
                </strong>
                <span className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-small text-muted">
                  <span className="flex items-center gap-1.5">
                    <CalendarDays size={14} /> Lịch hẹn {formatDateTime(request.appointmentStart)}
                  </span>
                  <span>Khách chọn ngày {formatDate(request.preferredDate)}</span>
                </span>
                {request.note && (
                  <p className="mb-0 mt-1 text-small text-muted">Ghi chú: {request.note}</p>
                )}
              </div>
              <div className="flex flex-wrap gap-2">
                {request.appointmentId !== null && (
                  <Button
                    variant="secondary"
                    size="sm"
                    pending={arrivingId === request.id}
                    disabled={arrivingId !== null}
                    onClick={() => arrive(request)}
                  >
                    Khách đã đến
                  </Button>
                )}
                <Button size="sm" onClick={() => navigate(`/staff/returns/${request.id}`)}>
                  Mở biên bản <ChevronRight size={14} />
                </Button>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
