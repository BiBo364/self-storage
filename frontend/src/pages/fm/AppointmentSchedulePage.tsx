import { CalendarDays, Check, ChevronLeft, ChevronRight, User } from "lucide-react";
import { useState } from "react";
import { formatDate, formatTime, isoDate } from "../../app/format";
import { fetchFacilityStaff, type FmStaff } from "../../app/fmStaff";
import { queryParam } from "../../app/routes";
import {
  assignAppointmentStaff,
  fetchAppointments,
  labelOf,
  loadErrorState,
  ORDER_STATUS_LABEL,
  toneOf,
  ORDER_STATUS_TONE,
  useFmAction,
  useFmLoad,
  type FmAppointment,
} from "../../app/services/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

function initialDate() {
  const fromQuery = queryParam("date");
  return fromQuery && /^\d{4}-\d{2}-\d{2}$/.test(fromQuery) ? fromQuery : isoDate(new Date());
}

function shiftDate(value: string, days: number) {
  const [year, month, day] = value.split("-").map(Number);
  return isoDate(new Date(year, month - 1, day + days));
}

function staffLabel(staff: FmStaff) {
  return staff.name ? `${staff.name} · ${staff.email}` : staff.email;
}

export function AppointmentSchedulePage() {
  const [date, setDate] = useState(initialDate);
  const [unassignedOnly, setUnassignedOnly] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const appointments = useFmLoad(`appointments:${date}`, (facility) =>
    fetchAppointments(facility.id, date),
  );
  const staff = useFmLoad("staff", (facility) => fetchFacilityStaff(facility.id));

  const list = (appointments.data ?? []).filter((item) => !unassignedOnly || item.staffId === null);
  const unassignedCount = (appointments.data ?? []).filter((item) => item.staffId === null).length;

  function changeDate(value: string) {
    if (!value) return;
    setDate(value);
    setNotice(null);
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Lịch hẹn &amp; phân công FS</h1>
        <p className="mt-1.5 text-small text-muted">
          Lịch check-in đang chờ của{" "}
          {appointments.facility ? `cơ sở ${appointments.facility.name}` : "cơ sở"} trong ngày. Mỗi
          lịch cần một FS tiếp đón; phân công xong đơn chuyển sang “Đang bàn giao”.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <label className="grid gap-1.5">
          <span className="text-label text-muted">Ngày hẹn</span>
          <div className={INPUT_WRAP}>
            <CalendarDays size={15} />
            <input
              type="date"
              className={INPUT_ELEMENT}
              value={date}
              onChange={(event) => changeDate(event.target.value)}
            />
          </div>
        </label>
        <Button variant="secondary" onClick={() => changeDate(shiftDate(date, -1))}>
          <ChevronLeft size={15} /> Hôm trước
        </Button>
        <Button variant="secondary" onClick={() => changeDate(isoDate(new Date()))}>
          Hôm nay
        </Button>
        <Button variant="secondary" onClick={() => changeDate(shiftDate(date, 1))}>
          Hôm sau <ChevronRight size={15} />
        </Button>
        <label className="ml-auto flex min-h-[42px] items-center gap-2 text-small text-ink max-[760px]:ml-0">
          <input
            type="checkbox"
            checked={unassignedOnly}
            onChange={(event) => setUnassignedOnly(event.target.checked)}
          />
          Chỉ lịch chưa có FS ({unassignedCount})
        </label>
      </div>

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}
      {staff.error && (
        <DemoNotice tone="error">Không tải được danh sách FS: {staff.error.message}</DemoNotice>
      )}

      {appointments.error ? (
        <SurfaceState
          {...loadErrorState(appointments.error, "lịch hẹn")}
          action={{ label: "Thử lại", onClick: appointments.reload }}
        />
      ) : !appointments.data ? (
        <SurfaceState variant="loading" title="Đang tải lịch hẹn…" />
      ) : list.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={`Không có lịch check-in chờ ngày ${formatDate(date)}`}
          description={
            unassignedOnly
              ? "Mọi lịch trong ngày đã có FS phụ trách."
              : "Chọn ngày khác để xem lịch."
          }
        />
      ) : (
        <div className={`grid gap-2 ${appointments.loading ? "opacity-60" : ""}`}>
          {list.map((appointment) => (
            <AppointmentRow
              key={appointment.id}
              appointment={appointment}
              staff={staff.data ?? []}
              onAssigned={(result, staffMember) => {
                setNotice(
                  `Đã phân công ${staffMember ? staffLabel(staffMember) : (result.staffEmail ?? "FS")} cho lịch của ${
                    result.customerName ?? result.customerEmail ?? `đơn #${result.orderId}`
                  }.`,
                );
                appointments.reload();
              }}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function AppointmentRow({
  appointment,
  staff,
  onAssigned,
}: {
  appointment: FmAppointment;
  staff: FmStaff[];
  onAssigned: (result: FmAppointment, staffMember: FmStaff | undefined) => void;
}) {
  const [staffId, setStaffId] = useState("");
  const action = useFmAction();
  const selectId = `assign-staff-${appointment.id}`;

  async function assign() {
    const outcome = await action.run("assign", () =>
      assignAppointmentStaff(appointment.id, Number(staffId)),
    );
    if (outcome.ok) {
      onAssigned(
        outcome.value,
        staff.find((member) => member.id === Number(staffId)),
      );
    }
  }

  return (
    <div className="grid grid-cols-[110px_minmax(0,1.3fr)_minmax(0,1fr)_minmax(0,1.5fr)] items-center gap-4 bg-surface p-4 max-[900px]:grid-cols-1">
      <div>
        <strong className="block text-stat text-ink tabular-nums">
          {formatTime(appointment.startedAt)}
        </strong>
        <span className="text-label text-muted">đến {formatTime(appointment.endAt)}</span>
      </div>
      <div className="min-w-0">
        <strong className="block truncate text-small font-semibold text-ink">
          {appointment.customerName ?? appointment.customerEmail ?? "—"}
        </strong>
        <span className="mt-0.5 block truncate text-label text-muted">
          {appointment.customerEmail ?? "—"}
        </span>
      </div>
      <div>
        <span className="block text-small text-ink">
          Đơn #{appointment.orderId ?? "—"} · Khoang {appointment.unitCode ?? "—"}
        </span>
        {appointment.orderStatus && (
          <span className="mt-1 inline-block">
            <StatusTag tone={toneOf(ORDER_STATUS_TONE, appointment.orderStatus)}>
              {labelOf(ORDER_STATUS_LABEL, appointment.orderStatus)}
            </StatusTag>
          </span>
        )}
      </div>
      <div>
        {appointment.staffId !== null ? (
          <span className="flex items-center gap-2 text-small text-ink">
            <User size={14} /> {appointment.staffEmail ?? `FS #${appointment.staffId}`}
          </span>
        ) : staff.length === 0 ? (
          <span className="text-label text-muted">Cơ sở chưa có FS để phân công.</span>
        ) : (
          <div className="flex flex-wrap items-center gap-2">
            <div className={`${INPUT_WRAP} min-w-[200px] flex-1`}>
              <label htmlFor={selectId} className="sr-only">
                Chọn FS
              </label>
              <select
                id={selectId}
                className={INPUT_ELEMENT}
                value={staffId}
                onChange={(event) => {
                  setStaffId(event.target.value);
                  action.clear();
                }}
              >
                <option value="">— Chọn FS —</option>
                {staff.map((member) => (
                  <option key={member.id} value={member.id}>
                    {staffLabel(member)}
                  </option>
                ))}
              </select>
            </div>
            <Button
              size="sm"
              pending={action.pending === "assign"}
              disabled={!staffId}
              onClick={assign}
            >
              {action.pending !== "assign" && <Check size={14} />} Phân công
            </Button>
          </div>
        )}
        {action.error && <DemoNotice tone="error">{action.error}</DemoNotice>}
      </div>
    </div>
  );
}
