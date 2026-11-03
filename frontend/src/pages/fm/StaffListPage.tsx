import { CalendarDays, LifeBuoy, Mail, Phone, Undo2 } from "lucide-react";
import { useState } from "react";
import {
  fmAppointments,
  fsStaff,
  returnRequests,
  supportRequests,
  type FsStaff,
} from "../../mocks/fm";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

type WorkItem = {
  id: string;
  label: string;
  icon: typeof CalendarDays;
};

function workItemsForStaff(staffId: string): WorkItem[] {
  const appointments = fmAppointments
    .filter((appt) => appt.staffId === staffId && appt.status === "Pending")
    .map((appt) => ({
      id: appt.id,
      label: `Lịch hẹn ${appt.type === "CHECKIN" ? "check-in" : "trả kho"} — ${appt.customerName} (${appt.unitCode}, ${appt.date})`,
      icon: CalendarDays,
    }));
  const returns = returnRequests
    .filter((request) => request.staffId === staffId && request.status === "Assigned")
    .map((request) => ({
      id: request.id,
      label: `Trả kho — ${request.customerName} (${request.unitCode})`,
      icon: Undo2,
    }));
  const support = supportRequests
    .filter((request) => request.staffId === staffId && request.status === "Assigned")
    .map((request) => ({
      id: request.id,
      label: `Hỗ trợ sự cố — khoang ${request.unitCode}`,
      icon: LifeBuoy,
    }));
  return [...appointments, ...returns, ...support];
}

export function StaffListPage() {
  const [openId, setOpenId] = useState<string | null>(null);
  const openStaff = fsStaff.find((staff) => staff.id === openId) ?? null;

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Danh sách nhân viên FS</h1>
        <p className="mt-1.5 text-small text-muted">
          Nhân viên hiện trường được gán vào cơ sở của bạn — chỉ xem, việc gán/xoá thuộc quyền
          Admin.
        </p>
      </div>

      {fsStaff.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Chưa có FS nào được gán vào cơ sở này"
          description="Nếu cần thêm nhân sự, gửi yêu cầu qua Business Operation Manager."
        />
      ) : (
        <div className="grid gap-2">
          {fsStaff.map((staff) => {
            const workload = workItemsForStaff(staff.id).length;
            return (
              <button
                key={staff.id}
                className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
                onClick={() => setOpenId(staff.id)}
              >
                <div>
                  <strong className="block text-small font-semibold text-ink">{staff.name}</strong>
                  <span className="mt-1 flex items-center gap-1.5 text-label text-muted">
                    <Mail size={13} /> {staff.email}
                  </span>
                  <span className="mt-1 block text-label text-muted">
                    Gán vào cơ sở từ {staff.assignedAt}
                  </span>
                </div>
                <StatusTag tone={workload > 0 ? "warning" : "neutral"}>
                  {workload > 0 ? `${workload} việc đang xử lý` : "Không có việc"}
                </StatusTag>
              </button>
            );
          })}
        </div>
      )}

      {openStaff && <StaffDetailPanel staff={openStaff} onClose={() => setOpenId(null)} />}
    </div>
  );
}

function StaffDetailPanel({ staff, onClose }: { staff: FsStaff; onClose: () => void }) {
  const items = workItemsForStaff(staff.id);

  return (
    <DetailPanel title={staff.name} eyebrow="Nhân viên FS" onClose={onClose}>
      <div className="grid gap-2 text-small text-muted">
        <span className="flex items-center gap-2">
          <Mail size={14} /> {staff.email}
        </span>
        <span className="flex items-center gap-2">
          <Phone size={14} /> {staff.phone}
        </span>
        <span>Gán vào cơ sở từ {staff.assignedAt}</span>
      </div>

      <h3 className="mt-6 text-label text-muted">Công việc đang được gán ({items.length})</h3>
      {items.length === 0 ? (
        <p className="mt-2 text-small text-muted">Hiện chưa có công việc nào được gán.</p>
      ) : (
        <div className="mt-2 grid gap-2">
          {items.map((item) => (
            <div
              key={item.id}
              className="flex items-center gap-2.5 bg-surface-subtle p-3 text-small text-ink"
            >
              <item.icon size={14} className="shrink-0 text-accent" />
              {item.label}
            </div>
          ))}
        </div>
      )}
    </DetailPanel>
  );
}
