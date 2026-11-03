import { Clock3, MapPin } from "lucide-react";
import { useEffect, useState } from "react";
import { ApiError } from "../../app/api";
import { fetchStaffAppointments, type StaffAppointment } from "../../app/staffSchedule";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const TIME_FORMAT = new Intl.DateTimeFormat("vi-VN", {
  timeZone: "Asia/Ho_Chi_Minh",
  hour: "2-digit",
  minute: "2-digit",
});

const STATUS: Record<string, { label: string; tone: "success" | "warning" | "neutral" }> = {
  Pending: { label: "Chờ khách đến", tone: "warning" },
  Done: { label: "Đã đến", tone: "success" },
  Canceled: { label: "Đã huỷ", tone: "neutral" },
};

const TYPE_LABEL: Record<string, string> = {
  CHECKIN: "Check-in",
  RETURN: "Trả kho",
  HANDOVER: "Bàn giao",
};

function pad(value: number) {
  return String(value).padStart(2, "0");
}

function todayIso() {
  const now = new Date();
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

function formatDateVN(value: string) {
  const [year, month, day] = value.split("-");
  return `${day}/${month}/${year}`;
}

function formatTimeRange(appointment: StaffAppointment) {
  if (!appointment.startedAt) return formatDateVN(appointment.date);
  const start = TIME_FORMAT.format(new Date(appointment.startedAt));
  if (!appointment.endAt) return start;
  return `${start}–${TIME_FORMAT.format(new Date(appointment.endAt))}`;
}

function typeLabel(type: string) {
  return TYPE_LABEL[type] ?? type;
}

function statusOf(status: string) {
  return STATUS[status] ?? { label: status, tone: "neutral" as const };
}

export function DailySchedulePage() {
  const [date, setDate] = useState(todayIso);
  const [appointments, setAppointments] = useState<StaffAppointment[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let active = true;

    (async () => {
      try {
        const list = await fetchStaffAppointments(date);
        if (!active) return;
        setAppointments(list);
      } catch (caught) {
        if (!active) return;
        setError(
          caught instanceof ApiError
            ? { status: caught.status, message: caught.message }
            : { status: 0, message: "Không thể kết nối tới máy chủ." },
        );
      } finally {
        if (active) setIsLoading(false);
      }
    })();

    return () => {
      active = false;
    };
  }, [date, reloadKey]);

  const pending = appointments.filter((appointment) => appointment.status === "Pending").length;
  const done = appointments.filter((appointment) => appointment.status === "Done").length;

  function changeDate(next: string) {
    if (!next) return;
    setDate(next);
    setIsLoading(true);
    setError(null);
  }

  function retry() {
    setIsLoading(true);
    setError(null);
    setReloadKey((key) => key + 1);
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Lịch làm việc trong ngày</h1>
          <p className="mt-1.5 text-small text-muted">
            Các lịch hẹn được FM phân công cho bạn tại cơ sở.
          </p>
        </div>
        <label className="grid gap-1 text-label text-muted">
          Ngày
          <input
            type="date"
            value={date}
            onChange={(event) => changeDate(event.target.value)}
            className="min-h-[42px] border border-border bg-surface px-3 text-small text-ink"
          />
        </label>
      </div>

      <div className="grid grid-cols-3 gap-2 max-[760px]:grid-cols-1">
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">
            {String(appointments.length).padStart(2, "0")}
          </strong>
          <span className="text-label text-muted">Lịch hẹn</span>
        </div>
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">{String(pending).padStart(2, "0")}</strong>
          <span className="text-label text-muted">Chờ khách đến</span>
        </div>
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">{String(done).padStart(2, "0")}</strong>
          <span className="text-label text-muted">Đã đến</span>
        </div>
      </div>

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải lịch làm việc…" />
      ) : error ? (
        error.status === 403 || error.status === 404 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xem lịch làm việc"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được lịch làm việc"
            description={error.message}
            action={{ label: "Thử lại", onClick: retry }}
          />
        )
      ) : appointments.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={`Không có lịch hẹn ngày ${formatDateVN(date)}`}
          description="Chọn ngày khác hoặc liên hệ FM nếu bạn cần được phân công thêm."
        />
      ) : (
        <div className="grid gap-3">
          {appointments.map((appointment) => {
            const status = statusOf(appointment.status);
            return (
              <article key={appointment.id} className="border border-border bg-surface p-5">
                <div className="flex flex-wrap items-start justify-between gap-4">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="inline-flex items-center gap-1.5 font-mono text-label text-muted">
                        <Clock3 size={14} /> {formatTimeRange(appointment)}
                      </span>
                      <span className="bg-surface-subtle px-2 py-1 text-label font-semibold text-muted">
                        {typeLabel(appointment.type)}
                      </span>
                      <StatusTag tone={status.tone}>{status.label}</StatusTag>
                    </div>
                    <h2 className="mb-0 mt-2 text-section text-ink">
                      {appointment.customer.name ?? appointment.customer.email}
                    </h2>
                    <p className="mb-0 mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-small text-muted">
                      <span className="flex items-center gap-1.5">
                        <MapPin size={14} /> {appointment.unitCode ?? "—"}
                      </span>
                      {appointment.customer.phone && <span>{appointment.customer.phone}</span>}
                    </p>
                  </div>
                  <div className="text-right max-[760px]:text-left">
                    {appointment.orderId !== null && (
                      <span className="block text-label text-muted">Đơn {appointment.orderId}</span>
                    )}
                    <span className="mt-0.5 block text-label text-muted">
                      {appointment.facility.name}
                    </span>
                  </div>
                </div>
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}
