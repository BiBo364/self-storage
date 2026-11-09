import { Check, User } from "lucide-react";
import { useEffect, useState } from "react";
import { ApiError } from "../../app/api";
import {
  assignAppointmentStaff,
  fetchFmAppointments,
  reassignAppointmentStaff,
  type FmAppointment,
} from "../../app/appointments";
import { fetchCurrentFmFacility, type FmFacility } from "../../app/fmFacility";
import { fetchFacilityStaff, type FmStaff } from "../../app/fmStaff";
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

function formatTimeRange(appointment: FmAppointment) {
  if (!appointment.startedAt) return formatDateVN(appointment.date);
  const start = TIME_FORMAT.format(new Date(appointment.startedAt));
  if (!appointment.endAt) return start;
  return `${start}–${TIME_FORMAT.format(new Date(appointment.endAt))}`;
}

function staffLabel(staff: FmStaff) {
  return staff.name ?? staff.email;
}

export function AppointmentSchedulePage() {
  const [facility, setFacility] = useState<FmFacility | null>(null);
  const [date, setDate] = useState(todayIso);
  const [appointments, setAppointments] = useState<FmAppointment[]>([]);
  const [staff, setStaff] = useState<FmStaff[]>([]);
  const [unassignedOnly, setUnassignedOnly] = useState(false);
  const [openId, setOpenId] = useState<number | null>(null);
  const [selectedStaffId, setSelectedStaffId] = useState("");
  const [assigning, setAssigning] = useState(false);
  const [assignError, setAssignError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let active = true;

    (async () => {
      try {
        const current = await fetchCurrentFmFacility();
        const [dayAppointments, facilityStaff] = await Promise.all([
          fetchFmAppointments(current.id, date),
          fetchFacilityStaff(current.id),
        ]);
        if (!active) return;
        setFacility(current);
        setAppointments(dayAppointments);
        setStaff(facilityStaff);
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

  const staffById = new Map(staff.map((member) => [member.id, member]));
  const openAppointment = appointments.find((item) => item.id === openId) ?? null;
  const filtered = unassignedOnly
    ? appointments.filter((item) => item.staffId === null)
    : appointments;

  function assignedStaffName(appointment: FmAppointment) {
    if (appointment.staffId === null) return null;
    return staffById.get(appointment.staffId)?.name ?? appointment.staffEmail;
  }

  function changeDate(next: string) {
    setDate(next);
    setIsLoading(true);
    setError(null);
    setOpenId(null);
    setAssignError(null);
  }

  function retry() {
    setIsLoading(true);
    setError(null);
    setReloadKey((key) => key + 1);
  }

  function openDetails(appointment: FmAppointment) {
    setOpenId(appointment.id);
    setSelectedStaffId("");
    setAssignError(null);
  }

  async function assign() {
    if (!openAppointment || !selectedStaffId) return;
    setAssigning(true);
    setAssignError(null);
    try {
      // Lịch đã có FS mà khách chưa đến: đổi FS (PUT); chưa có thì phân công (POST).
      if (openAppointment.staffId === null) {
        await assignAppointmentStaff(openAppointment.id, Number(selectedStaffId));
      } else {
        await reassignAppointmentStaff(openAppointment.id, Number(selectedStaffId));
      }
      setOpenId(null);
      setReloadKey((key) => key + 1);
    } catch (caught) {
      setAssignError(
        caught instanceof ApiError ? caught.message : "Không thể phân công nhân viên.",
      );
      if (caught instanceof ApiError && caught.status === 409) {
        setReloadKey((key) => key + 1);
      }
    } finally {
      setAssigning(false);
    }
  }

  if (isLoading) {
    return <SurfaceState variant="loading" title="Đang tải lịch hẹn…" />;
  }

  if (error) {
    if (error.status === 403 || error.status === 404) {
      return (
        <SurfaceState
          variant="forbidden"
          title="Bạn không có quyền xem lịch hẹn cơ sở"
          description={error.message}
        />
      );
    }
    return (
      <SurfaceState
        variant="error"
        title="Không tải được lịch hẹn"
        description={error.message}
        action={{ label: "Thử lại", onClick: retry }}
      />
    );
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Lịch hẹn &amp; phân công FS</h1>
        <p className="mt-1.5 text-small text-muted">
          Lịch check-in tại {facility?.name ?? "cơ sở của bạn"} — chọn ngày để phân công FS.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-4">
        <label className="grid max-w-[200px] gap-1.5 text-label text-muted">
          Ngày
          <input
            className="h-11 rounded-[2px] border border-rule-strong bg-surface px-3 text-small text-ink outline-0 transition-colors focus:border-brand"
            type="date"
            value={date}
            onChange={(event) => event.target.value && changeDate(event.target.value)}
          />
        </label>

        <div className="max-w-[280px]">
          <FilterSelect
            label="Lọc lịch hẹn"
            value={unassignedOnly ? "unassigned" : "all"}
            onChange={(value) => setUnassignedOnly(value === "unassigned")}
          >
            <option value="all">Tất cả lịch hẹn</option>
            <option value="unassigned">Chỉ chưa phân công</option>
          </FilterSelect>
        </div>
      </div>

      {filtered.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={`Không có lịch check-in ngày ${formatDateVN(date)} phù hợp bộ lọc`}
        />
      ) : (
        <div className="grid gap-2">
          {filtered.map((appointment) => {
            const assignedName = assignedStaffName(appointment);
            return (
              <button
                key={appointment.id}
                className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
                onClick={() => openDetails(appointment)}
              >
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <StatusTag tone="success">Check-in</StatusTag>
                    {appointment.rebooked && (
                      <StatusTag tone="info">Đặt lại lần {appointment.attempt}</StatusTag>
                    )}
                    <strong className="text-small font-semibold text-ink">
                      {appointment.customerName ?? appointment.customerEmail}
                    </strong>
                  </div>
                  <span className="mt-1.5 block text-label text-muted">
                    Khoang {appointment.unitCode ?? "—"} · {formatTimeRange(appointment)}
                  </span>
                  {appointment.rebooked &&
                    (appointment.rebookReason ?? appointment.previousCancelReason) && (
                      <span className="mt-1 block text-label text-muted">
                        Lý do đặt lại:{" "}
                        {appointment.rebookReason ?? appointment.previousCancelReason}
                      </span>
                    )}
                </div>
                <span className="flex items-center gap-1.5 whitespace-nowrap">
                  {assignedName ? (
                    <StatusTag tone="success">
                      <User size={12} /> {assignedName}
                    </StatusTag>
                  ) : (
                    <StatusTag tone="warning">Chưa phân công</StatusTag>
                  )}
                </span>
              </button>
            );
          })}
        </div>
      )}

      {openAppointment && (
        <DetailPanel
          title={openAppointment.customerName ?? openAppointment.customerEmail}
          eyebrow={`Check-in · ${formatDateVN(openAppointment.date)}`}
          onClose={() => setOpenId(null)}
        >
          <div className="grid gap-2 text-small text-muted">
            <span>{openAppointment.customerEmail}</span>
            <span>Khoang {openAppointment.unitCode ?? "—"}</span>
            <span>{formatTimeRange(openAppointment)}</span>
            {openAppointment.rebooked && (
              <div className="mt-1 grid gap-1 border-t border-rule pt-2 text-label">
                <span>Đặt lại lần {openAppointment.attempt}</span>
                {(openAppointment.rebookReason ?? openAppointment.previousCancelReason) && (
                  <span>
                    Lý do đặt lại:{" "}
                    {openAppointment.rebookReason ?? openAppointment.previousCancelReason}
                  </span>
                )}
              </div>
            )}
          </div>

          {assignError && (
            <div className="mt-4">
              <DemoNotice tone="error">{assignError}</DemoNotice>
            </div>
          )}

          {assignedStaffName(openAppointment) && (
            <div className="mt-6">
              <DemoNotice tone="success">
                Đã phân công {assignedStaffName(openAppointment)} phụ trách lịch hẹn này.
              </DemoNotice>
            </div>
          )}

          {(openAppointment.staffId === null || openAppointment.status === "Pending") && (
            <>
              {staff.length === 0 ? (
                <div className="mt-6">
                  <DemoNotice tone="pending">
                    Cơ sở chưa có FS đang hoạt động để phân công.
                  </DemoNotice>
                </div>
              ) : (
                <>
                  <label className="mt-6 grid gap-1.5 text-label text-muted">
                    {openAppointment.staffId === null
                      ? "Chọn nhân viên FS"
                      : "Đổi sang nhân viên FS"}
                    <select
                      className="rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink"
                      value={selectedStaffId}
                      onChange={(event) => setSelectedStaffId(event.target.value)}
                    >
                      <option value="">— Chọn nhân viên —</option>
                      {staff
                        .filter((member) => member.id !== openAppointment.staffId)
                        .map((member) => (
                          <option key={member.id} value={member.id}>
                            {staffLabel(member)}
                          </option>
                        ))}
                    </select>
                  </label>
                  <div className="mt-5">
                    <Button pending={assigning} disabled={!selectedStaffId} onClick={assign}>
                      {!assigning && <Check size={16} />}
                      {openAppointment.staffId === null ? "Phân công" : "Đổi FS"}
                    </Button>
                  </div>
                </>
              )}
            </>
          )}
        </DetailPanel>
      )}
    </div>
  );
}
