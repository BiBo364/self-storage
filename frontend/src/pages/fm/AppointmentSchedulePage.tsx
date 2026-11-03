import { Check, Lock, User } from "lucide-react";
import { useEffect, useState } from "react";
import { ApiError } from "../../app/api";
import {
  assignAppointmentStaff,
  fetchAppointmentsOfDay,
  fetchFacilityStaff,
  formatIsoDate,
  formatTimeSlot,
  todayIsoDate,
  type AppointmentType,
  type FacilityStaff,
  type FmAppointment,
} from "../../app/appointments";
import { currentFmFacilityId } from "../../app/fmFacility";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { SurfaceState } from "../../components/ui/SurfaceState";

const TYPE_LABEL: Record<AppointmentType, string> = {
  CHECKIN: "Check-in",
  HANDOVER: "Bàn giao",
  RETURN: "Trả kho",
};

const TYPE_BADGE: Record<AppointmentType, string> = {
  CHECKIN: "bg-success/14 text-success",
  HANDOVER: "bg-brand-soft text-brand",
  RETURN: "bg-info/14 text-info",
};

const FILTER_SELECT =
  "min-w-[170px] rounded-sm border border-border bg-surface px-[11px] py-[10px] pr-[30px] text-[12px] font-semibold text-ink";

type DayState =
  | { kind: "loading" }
  | { kind: "no-facility" }
  | { kind: "error"; status: number | null; message: string }
  | { kind: "ready"; items: FmAppointment[] };

/** Hàm tải thuần (không setState) để effect chỉ ghi state sau `await`. */
async function loadDay(facilityId: number, date: string): Promise<DayState> {
  try {
    return { kind: "ready", items: await fetchAppointmentsOfDay(facilityId, date) };
  } catch (error) {
    return error instanceof ApiError
      ? { kind: "error", status: error.status, message: error.message }
      : { kind: "error", status: null, message: "Không tải được lịch hẹn, thử lại sau." };
  }
}

/** Chỉ phân công được lịch check-in còn chờ và đơn đang chờ phân công (BE vẫn kiểm lại). */
function canAssign(appointment: FmAppointment) {
  return (
    appointment.type === "CHECKIN" &&
    appointment.status === "Pending" &&
    appointment.staffId === null &&
    appointment.orderStatus === "Scheduled"
  );
}

export function AppointmentSchedulePage() {
  const facilityId = currentFmFacilityId();
  const [date, setDate] = useState(todayIsoDate);
  const [day, setDay] = useState<DayState>(
    facilityId === null ? { kind: "no-facility" } : { kind: "loading" },
  );
  const [staff, setStaff] = useState<FacilityStaff[]>([]);
  const [unassignedOnly, setUnassignedOnly] = useState(false);
  const [openId, setOpenId] = useState<number | null>(null);
  const [selectedStaffId, setSelectedStaffId] = useState("");
  const [busyId, setBusyId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    if (facilityId === null) return;
    let active = true;
    async function load(id: number) {
      const [next, members] = await Promise.all([
        loadDay(id, date),
        fetchFacilityStaff(id).catch(() => [] as FacilityStaff[]),
      ]);
      if (!active) return;
      setDay(next);
      setStaff(members);
    }
    void load(facilityId);
    return () => {
      active = false;
    };
  }, [facilityId, date]);

  const items = day.kind === "ready" ? day.items : [];
  const list = items.filter((item) => !unassignedOnly || item.staffId === null);
  const openAppointment = items.find((item) => item.id === openId) ?? null;

  // setState ở event handler, không phải trong effect.
  function changeDate(next: string) {
    if (!next || next === date) return;
    setDay({ kind: "loading" });
    setOpenId(null);
    setDate(next);
  }

  async function retry() {
    if (facilityId === null) return;
    setDay({ kind: "loading" });
    setDay(await loadDay(facilityId, date));
  }

  function openDetails(appointment: FmAppointment) {
    setOpenId(appointment.id);
    setSelectedStaffId("");
    setActionError(null);
  }

  function replaceItem(updated: FmAppointment) {
    setDay((current) =>
      current.kind === "ready"
        ? {
            kind: "ready",
            items: current.items.map((item) => (item.id === updated.id ? updated : item)),
          }
        : current,
    );
  }

  async function assign(appointment: FmAppointment) {
    if (!selectedStaffId) return;
    setBusyId(appointment.id);
    setActionError(null);
    try {
      replaceItem(await assignAppointmentStaff(appointment.id, Number(selectedStaffId)));
    } catch (error) {
      // 409 (đã có FS, không còn chờ, đơn không Scheduled), 400 (FS khác cơ sở), 403, 404: câu của BE.
      setActionError(
        error instanceof ApiError ? error.message : "Không phân công được, thử lại sau.",
      );
      if (error instanceof ApiError && error.status === 409 && facilityId !== null) {
        const latest = await loadDay(facilityId, date);
        if (latest.kind === "ready") setDay(latest);
      }
    } finally {
      setBusyId(null);
    }
  }

  return (
    <main>
      <div className="mb-8">
        <h1 className="m-0 text-[28px] tracking-[-0.03em] text-ink">Lịch hẹn &amp; phân công FS</h1>
        <p className="mt-2 text-[13px] text-muted">
          Lịch check-in và trả kho tại cơ sở của bạn, theo ngày.
        </p>
      </div>

      <div className="mb-6">
        <DemoNotice tone="pending">
          Chỉ phân công FS cho lịch <strong>check-in</strong> đang chờ. Lịch trả kho được phân công
          từ hàng chờ yêu cầu trả kho — ở đây chỉ xem.
        </DemoNotice>
      </div>

      <div className="mb-6 flex flex-wrap items-center gap-3">
        <input
          type="date"
          className={FILTER_SELECT}
          value={date}
          disabled={day.kind === "no-facility"}
          onChange={(event) => changeDate(event.target.value)}
        />
        <select
          className={FILTER_SELECT}
          value={unassignedOnly ? "unassigned" : "all"}
          onChange={(event) => setUnassignedOnly(event.target.value === "unassigned")}
        >
          <option value="all">Tất cả lịch hẹn</option>
          <option value="unassigned">Chỉ chưa phân công</option>
        </select>
      </div>

      {day.kind === "loading" && <SurfaceState variant="loading" title="Đang tải lịch hẹn…" />}

      {day.kind === "no-facility" && (
        <SurfaceState
          variant="forbidden"
          title="Chưa xác định được cơ sở của bạn"
          description="Tài khoản này chưa được gán cơ sở để xem lịch hẹn."
        />
      )}

      {day.kind === "error" && (
        <SurfaceState
          variant={day.status === 403 ? "forbidden" : "error"}
          title={
            day.status === 403 ? "Bạn không có quyền xem lịch hẹn này" : "Không tải được lịch hẹn"
          }
          description={day.message}
          action={
            day.status === 403 ? undefined : { label: "Thử lại", onClick: () => void retry() }
          }
        />
      )}

      {day.kind === "ready" && list.length === 0 && (
        <SurfaceState
          variant="empty"
          title={`Không có lịch hẹn nào ngày ${formatIsoDate(date)} phù hợp bộ lọc`}
        />
      )}

      {day.kind === "ready" && list.length > 0 && (
        <section>
          <p className="m-0 mb-3 font-mono text-[11px] font-bold uppercase tracking-[0.08em] text-brand">
            {formatIsoDate(date)}
          </p>
          <div className="grid gap-3">
            {list.map((appointment) => {
              const isReadOnly = appointment.type !== "CHECKIN";
              return (
                <button
                  key={appointment.id}
                  className="flex items-start justify-between gap-5 border border-border bg-surface p-5 text-left transition-colors hover:border-brand disabled:cursor-default disabled:hover:border-border max-[760px]:flex-col"
                  onClick={() => !isReadOnly && openDetails(appointment)}
                  disabled={isReadOnly}
                >
                  <div>
                    <div className="flex items-center gap-2.5">
                      <span
                        className={`rounded-full px-2 py-0.5 text-[10px] font-bold ${TYPE_BADGE[appointment.type]}`}
                      >
                        {TYPE_LABEL[appointment.type]}
                      </span>
                      <p className="m-0 text-[15px] font-bold text-ink">
                        {appointment.customerEmail}
                      </p>
                    </div>
                    <p className="mb-0 mt-1.5 text-[13px] text-muted">
                      Khoang {appointment.unitCode ?? "—"} ·{" "}
                      {formatTimeSlot(appointment.startedAt, appointment.endAt)}
                    </p>
                  </div>
                  <span className="flex items-center gap-1.5 whitespace-nowrap text-[12px] font-bold text-muted">
                    {isReadOnly && <Lock size={13} />}
                    {appointment.staffEmail ? (
                      <>
                        <User size={14} /> {appointment.staffEmail}
                      </>
                    ) : (
                      <span className="rounded-full bg-warning/14 px-2 py-0.5 text-[11px] font-extrabold text-warning">
                        Chưa phân công
                      </span>
                    )}
                  </span>
                </button>
              );
            })}
          </div>
        </section>
      )}

      {openAppointment && (
        <DetailPanel
          title={openAppointment.customerEmail}
          eyebrow={`${TYPE_LABEL[openAppointment.type]} · ${formatIsoDate(openAppointment.date)}`}
          onClose={() => setOpenId(null)}
        >
          <div className="grid gap-2.5 text-[13px] text-muted">
            <span>Khoang {openAppointment.unitCode ?? "—"}</span>
            <span>{formatTimeSlot(openAppointment.startedAt, openAppointment.endAt)}</span>
            {openAppointment.orderId !== null && (
              <span>
                Đơn {openAppointment.orderId} · {openAppointment.orderStatus}
              </span>
            )}
          </div>

          {actionError && (
            <div className="mt-5">
              <DemoNotice tone="error">{actionError}</DemoNotice>
            </div>
          )}

          {openAppointment.staffEmail ? (
            <div className="mt-6">
              <DemoNotice tone="success">
                Đã phân công {openAppointment.staffEmail} phụ trách lịch hẹn này.
              </DemoNotice>
            </div>
          ) : canAssign(openAppointment) ? (
            <>
              <label className="mt-6 grid gap-1.5 text-[12px] font-bold text-ink">
                Chọn nhân viên FS
                <select
                  className="rounded-sm border border-border bg-surface-subtle px-3 py-2.5 text-[13px] text-ink"
                  value={selectedStaffId}
                  onChange={(event) => setSelectedStaffId(event.target.value)}
                >
                  <option value="">— Chọn nhân viên —</option>
                  {staff.map((member) => (
                    <option key={member.id} value={member.id}>
                      {member.email}
                    </option>
                  ))}
                </select>
              </label>
              {staff.length === 0 && (
                <p className="mb-0 mt-2 text-[12px] text-danger">
                  Cơ sở chưa có nhân viên FS đang hoạt động.
                </p>
              )}
              <div className="mt-5">
                <Button
                  pending={busyId === openAppointment.id}
                  disabled={!selectedStaffId}
                  onClick={() => void assign(openAppointment)}
                >
                  {busyId !== openAppointment.id && <Check size={16} />}
                  Phân công
                </Button>
              </div>
            </>
          ) : (
            <div className="mt-6">
              <DemoNotice tone="pending">
                Lịch này không ở trạng thái chờ phân công (lịch {openAppointment.status}, đơn{" "}
                {openAppointment.orderStatus ?? "—"}).
              </DemoNotice>
            </div>
          )}
        </DetailPanel>
      )}
    </main>
  );
}
