import { Clock3, MapPin } from "lucide-react";
import { useEffect, useState } from "react";
import { ApiError } from "../../app/api";
import {
  arriveStaffAppointment,
  cancelStaffAppointment,
  fetchStaffAppointments,
  type StaffAppointment,
} from "../../app/staffSchedule";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const TIME_FORMAT = new Intl.DateTimeFormat("vi-VN", {
  timeZone: "Asia/Ho_Chi_Minh",
  hour: "2-digit",
  minute: "2-digit",
});

const STATUS: Record<string, { label: string; tone: "success" | "warning" | "neutral" }> = {
  Pending: { label: "Đang chờ", tone: "warning" },
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
  const [statusFilter, setStatusFilter] = useState("all");
  const [action, setAction] = useState<{
    appointment: StaffAppointment;
    kind: "arrive" | "cancel";
  } | null>(null);
  const [reason, setReason] = useState("");
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(0);
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
        setNow(Date.now());
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

  // now is set when the day loads; the schedule re-evaluates expiration on refresh.

  const pending = appointments.filter((appointment) => appointment.status === "Pending").length;
  const done = appointments.filter((appointment) => appointment.status === "Done").length;
  const filtered =
    statusFilter === "all"
      ? appointments
      : appointments.filter((appointment) => appointment.status === statusFilter);

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

  function openAction(appointment: StaffAppointment, kind: "arrive" | "cancel") {
    setAction({ appointment, kind });
    setReason("");
    setActionError(null);
  }

  async function submitAction() {
    if (!action) return;
    if (action.kind === "cancel" && !reason.trim()) {
      setActionError("Vui lòng nhập lý do huỷ");
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      if (action.kind === "arrive") {
        await arriveStaffAppointment(action.appointment.id);
      } else {
        await cancelStaffAppointment(action.appointment.id, reason.trim());
      }
      setAction(null);
      setReloadKey((key) => key + 1);
    } catch (caught) {
      setActionError(
        caught instanceof ApiError ? caught.message : "Không thực hiện được thao tác.",
      );
      if (caught instanceof ApiError && caught.status === 409) {
        setReloadKey((key) => key + 1);
      }
    } finally {
      setBusy(false);
    }
  }

  function isExpired(appointment: StaffAppointment) {
    return appointment.endAt !== null && new Date(appointment.endAt).getTime() < now;
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
        <div className="flex flex-wrap items-end gap-3">
          <label className="grid gap-1 text-label text-muted">
            Ngày
            <input
              type="date"
              value={date}
              onChange={(event) => changeDate(event.target.value)}
              className="min-h-[42px] border border-border bg-surface px-3 text-small text-ink"
            />
          </label>
          <div className="min-w-[170px]">
            <FilterSelect label="Lọc trạng thái" value={statusFilter} onChange={setStatusFilter}>
              <option value="all">Tất cả</option>
              <option value="Pending">Đang chờ</option>
              <option value="Done">Đã đến</option>
              <option value="Canceled">Đã huỷ</option>
            </FilterSelect>
          </div>
        </div>
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
          <span className="text-label text-muted">Đang chờ</span>
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
      ) : filtered.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={
            appointments.length === 0
              ? `Không có lịch hẹn ngày ${formatDateVN(date)}`
              : "Không có lịch ở trạng thái này"
          }
          description="Chọn ngày khác hoặc liên hệ FM nếu bạn cần được phân công thêm."
        />
      ) : (
        <div className="grid gap-3">
          {filtered.map((appointment) => {
            const status = statusOf(appointment.status);
            return (
              <article key={appointment.id} className="border border-border bg-surface p-5">
                <div className="flex flex-wrap items-start justify-between gap-4">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="inline-flex items-center gap-1.5 font-mono text-label text-muted">
                        <Clock3 size={14} /> {formatTimeRange(appointment)}
                      </span>
                      {appointment.type !== "CHECKIN" && (
                        <span className="bg-surface-subtle px-2 py-1 text-label font-semibold text-muted">
                          {typeLabel(appointment.type)}
                        </span>
                      )}
                      {status && <StatusTag tone={status.tone}>{status.label}</StatusTag>}
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

                {appointment.status === "Pending" && (
                  <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
                    <span className="text-label text-muted">
                      {isExpired(appointment)
                        ? "Đã quá giờ — chờ cron no-show."
                        : "Khách chưa đến."}
                    </span>
                    <div className="flex flex-wrap gap-2">
                      <Button onClick={() => openAction(appointment, "arrive")}>
                        Khách đã đến
                      </Button>
                      <Button
                        variant="secondary"
                        disabled={isExpired(appointment)}
                        onClick={() => openAction(appointment, "cancel")}
                      >
                        Hủy
                      </Button>
                    </div>
                  </div>
                )}
              </article>
            );
          })}
        </div>
      )}

      {action && (
        <DetailPanel
          title={action.kind === "arrive" ? "Xác nhận khách đã đến" : "Huỷ lịch hẹn"}
          eyebrow={`Check-in · ${formatDateVN(action.appointment.date)}`}
          onClose={() => setAction(null)}
        >
          <div className="grid gap-2 text-small text-muted">
            <span>{action.appointment.customer.name ?? action.appointment.customer.email}</span>
            <span>
              {formatTimeRange(action.appointment)} · {action.appointment.unitCode ?? "—"}
            </span>
            <span>{action.appointment.facility.name}</span>
          </div>

          {actionError && (
            <div className="mt-4">
              <DemoNotice tone="error">{actionError}</DemoNotice>
            </div>
          )}

          {action.kind === "cancel" && (
            <label className="mt-4 grid gap-1.5 text-label text-muted">
              Lý do huỷ
              <textarea
                className="min-h-[90px] rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink outline-0 transition-colors focus:border-brand"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                placeholder="Ví dụ: Khách yêu cầu huỷ"
                maxLength={1000}
              />
            </label>
          )}

          <div className="mt-5 flex flex-wrap gap-2">
            <Button pending={busy} onClick={submitAction}>
              {action.kind === "arrive" ? "Xác nhận khách đã đến" : "Huỷ lịch"}
            </Button>
            <Button variant="secondary" onClick={() => setAction(null)}>
              Đóng
            </Button>
          </div>
        </DetailPanel>
      )}
    </div>
  );
}
