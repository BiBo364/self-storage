import { Check, Lock, User } from "lucide-react";
import { useState } from "react";
import {
  assignAppointmentStaff,
  fmAppointments,
  fsStaff,
  type FmAppointment,
} from "../../mocks/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const TYPE_LABEL: Record<FmAppointment["type"], string> = {
  CHECKIN: "Check-in",
  RETURN: "Trả kho",
};

function staffName(staffId: string | null) {
  return fsStaff.find((staff) => staff.id === staffId)?.name ?? null;
}

function groupByDate(items: FmAppointment[]) {
  const groups = new Map<string, FmAppointment[]>();
  for (const item of items) {
    const bucket = groups.get(item.date) ?? [];
    bucket.push(item);
    groups.set(item.date, bucket);
  }
  return Array.from(groups.entries());
}

export function AppointmentSchedulePage() {
  const [, refresh] = useState(0);
  const [unassignedOnly, setUnassignedOnly] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);
  const [selectedStaffId, setSelectedStaffId] = useState("");
  const [busyId, setBusyId] = useState<string | null>(null);

  const openAppointment = fmAppointments.find((item) => item.id === openId) ?? null;

  const list = fmAppointments.filter((item) => !unassignedOnly || item.staffId === null);
  const groups = groupByDate(list);

  function openDetails(appointment: FmAppointment) {
    setOpenId(appointment.id);
    setSelectedStaffId("");
  }

  function assign(appointment: FmAppointment) {
    if (!selectedStaffId) return;
    setBusyId(appointment.id);
    window.setTimeout(() => {
      setBusyId(null);
      assignAppointmentStaff(appointment.id, selectedStaffId);
      refresh((count) => count + 1);
      setOpenId(null);
    }, 700);
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Lịch hẹn &amp; phân công FS</h1>
        <p className="mt-1.5 text-small text-muted">
          Lịch check-in và trả kho tại cơ sở của bạn, theo ngày.
        </p>
      </div>

      <DemoNotice tone="pending">
        Lịch <strong>trả kho</strong> được hệ thống tự phân công từ hàng chờ yêu cầu trả kho — ở đây
        chỉ xem, không phân công lại để tránh trùng lặp.
      </DemoNotice>

      <div className="max-w-[320px]">
        <FilterSelect
          label="Lọc lịch hẹn"
          value={unassignedOnly ? "unassigned" : "all"}
          onChange={(value) => setUnassignedOnly(value === "unassigned")}
        >
          <option value="all">Tất cả lịch hẹn</option>
          <option value="unassigned">Chỉ chưa phân công</option>
        </FilterSelect>
      </div>

      {groups.length === 0 ? (
        <SurfaceState variant="empty" title="Không có lịch hẹn nào phù hợp bộ lọc" />
      ) : (
        <div className="grid gap-6">
          {groups.map(([date, dayItems]) => (
            <section key={date}>
              <p className="mb-2 text-label text-muted">{date}</p>
              <div className="grid gap-2">
                {dayItems.map((appointment) => {
                  const assignedName = staffName(appointment.staffId);
                  const isReadOnly = appointment.type === "RETURN";
                  return (
                    <button
                      key={appointment.id}
                      className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle disabled:cursor-default disabled:hover:bg-surface"
                      onClick={() => !isReadOnly && openDetails(appointment)}
                      disabled={isReadOnly}
                    >
                      <div>
                        <div className="flex flex-wrap items-center gap-2">
                          <StatusTag tone={appointment.type === "CHECKIN" ? "success" : "info"}>
                            {TYPE_LABEL[appointment.type]}
                          </StatusTag>
                          <strong className="text-small font-semibold text-ink">
                            {appointment.customerName}
                          </strong>
                        </div>
                        <span className="mt-1.5 block text-label text-muted">
                          Khoang {appointment.unitCode} · {appointment.timeSlot}
                        </span>
                      </div>
                      <span className="flex items-center gap-1.5 whitespace-nowrap">
                        {isReadOnly && <Lock size={13} className="text-muted" />}
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
            </section>
          ))}
        </div>
      )}

      {openAppointment && (
        <DetailPanel
          title={openAppointment.customerName}
          eyebrow={`${TYPE_LABEL[openAppointment.type]} · ${openAppointment.date}`}
          onClose={() => setOpenId(null)}
        >
          <div className="grid gap-2 text-small text-muted">
            <span>Khoang {openAppointment.unitCode}</span>
            <span>{openAppointment.timeSlot}</span>
          </div>

          {staffName(openAppointment.staffId) ? (
            <div className="mt-6">
              <DemoNotice tone="success">
                Đã phân công {staffName(openAppointment.staffId)} phụ trách lịch hẹn này.
              </DemoNotice>
            </div>
          ) : (
            <>
              <label className="mt-6 grid gap-1.5 text-label text-muted">
                Chọn nhân viên FS
                <select
                  className="rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink"
                  value={selectedStaffId}
                  onChange={(event) => setSelectedStaffId(event.target.value)}
                >
                  <option value="">— Chọn nhân viên —</option>
                  {fsStaff.map((staff) => (
                    <option key={staff.id} value={staff.id}>
                      {staff.name} · {staff.phone}
                    </option>
                  ))}
                </select>
              </label>
              <div className="mt-5">
                <Button
                  pending={busyId === openAppointment.id}
                  disabled={!selectedStaffId}
                  onClick={() => assign(openAppointment)}
                >
                  {busyId !== openAppointment.id && <Check size={16} />}
                  Phân công
                </Button>
              </div>
            </>
          )}
        </DetailPanel>
      )}
    </div>
  );
}
