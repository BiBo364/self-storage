import { ChevronRight, Clock3, MapPin } from "lucide-react";
import { useState } from "react";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";
import { fsAppointments, TODAY, type FsAppointment } from "../../mocks/fs";

const STATUS: Record<
  FsAppointment["status"],
  { label: string; tone: "success" | "warning" | "neutral" }
> = {
  Pending: { label: "Chờ tiếp nhận", tone: "warning" },
  Done: { label: "Đã đến", tone: "success" },
  Canceled: { label: "Đã huỷ", tone: "neutral" },
};

function formatDate(iso: string) {
  const date = new Date(`${iso}T00:00:00`);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${pad(date.getDate())}/${pad(date.getMonth() + 1)}/${date.getFullYear()}`;
}

export function DailySchedulePage({ navigate }: { navigate: Navigate }) {
  const [date, setDate] = useState(TODAY);
  const appointments = fsAppointments.filter((appointment) => appointment.date === date);
  const pending = appointments.filter((appointment) => appointment.status === "Pending").length;
  const done = appointments.filter((appointment) => appointment.status === "Done").length;

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
            onChange={(event) => setDate(event.target.value)}
            className="min-h-[42px] border border-border bg-surface px-3 text-small text-ink"
          />
        </label>
      </div>

      <DemoNotice tone="info">
        Prototype — dữ liệu minh hoạ; API lịch hẹn FS chưa có. Ngày đang xem: {formatDate(date)}.
      </DemoNotice>

      <div className="grid grid-cols-3 gap-2 max-[760px]:grid-cols-1">
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">
            {String(appointments.length).padStart(2, "0")}
          </strong>
          <span className="text-label text-muted">Lịch hẹn</span>
        </div>
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">{String(pending).padStart(2, "0")}</strong>
          <span className="text-label text-muted">Chờ tiếp nhận</span>
        </div>
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">{String(done).padStart(2, "0")}</strong>
          <span className="text-label text-muted">Đã đến</span>
        </div>
      </div>

      {appointments.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Không có lịch hẹn trong ngày"
          description="Chọn ngày khác hoặc liên hệ FM nếu bạn cần được phân công thêm."
        />
      ) : (
        <div className="grid gap-3">
          {appointments.map((appointment) => {
            const status = STATUS[appointment.status];
            return (
              <article key={appointment.id} className="border border-border bg-surface p-5">
                <div className="flex flex-wrap items-start justify-between gap-4">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="inline-flex items-center gap-1.5 font-mono text-label text-muted">
                        <Clock3 size={14} /> {appointment.timeSlot}
                      </span>
                      <span className="bg-surface-subtle px-2 py-1 text-label font-semibold text-muted">
                        {appointment.type === "CHECKIN" ? "Check-in" : "Trả kho"}
                      </span>
                      <StatusTag tone={status.tone}>{status.label}</StatusTag>
                    </div>
                    <h2 className="mb-0 mt-2 text-section text-ink">{appointment.customerName}</h2>
                    <p className="mb-0 mt-1 flex items-center gap-1.5 text-small text-muted">
                      <MapPin size={14} /> {appointment.unitCode} · {appointment.unitType}
                    </p>
                  </div>
                  <div className="text-right max-[760px]:text-left">
                    <span className="block text-label text-muted">{appointment.orderCode}</span>
                    <span className="mt-0.5 block text-label text-muted">
                      {appointment.facility}
                    </span>
                  </div>
                </div>
                <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
                  <span className="text-label text-muted">
                    {appointment.status === "Done"
                      ? "Khách đã đến — biên bản đã chốt."
                      : appointment.type === "CHECKIN"
                        ? "Mở checklist để xác minh và bàn giao."
                        : "Checklist trả kho chưa có trong prototype."}
                  </span>
                  {appointment.type === "CHECKIN" ? (
                    <Button
                      size="sm"
                      variant={appointment.status === "Done" ? "secondary" : "primary"}
                      onClick={() => navigate(`/staff/appointments/${appointment.id}/handover`)}
                    >
                      {appointment.status === "Done" ? "Xem biên bản" : "Mở checklist"}
                      <ChevronRight size={14} />
                    </Button>
                  ) : (
                    <Button size="sm" variant="secondary" disabled>
                      Checklist trả kho — sắp có
                    </Button>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}
