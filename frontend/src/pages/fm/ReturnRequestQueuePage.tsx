import { CalendarClock, Check, User } from "lucide-react";
import { useState } from "react";
import { formatDate, formatDateTime, formatTime } from "../../app/format";
import { fetchFacilityStaff, type FmStaff } from "../../app/fmStaff";
import {
  assignReturnRequest,
  fetchReturnRequests,
  labelOf,
  loadErrorState,
  toneOf,
  useFmAction,
  useFmLoad,
  type ReturnRequest,
} from "../../app/services/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const STATUS_LABEL: Record<string, string> = {
  Pending: "Chờ phân công",
  Assigned: "Đã phân công",
  Completed: "Đã hoàn tất",
  Canceled: "Đã huỷ",
};

const STATUS_TONE: Record<string, "success" | "warning" | "danger" | "info" | "neutral"> = {
  Pending: "warning",
  Assigned: "info",
  Completed: "success",
  Canceled: "neutral",
};

const TABS = ["Pending", "Assigned"];

function staffLabel(staff: FmStaff) {
  return staff.name ? `${staff.name} · ${staff.email}` : staff.email;
}

export function ReturnRequestQueuePage() {
  const [tab, setTab] = useState("Pending");
  const [notice, setNotice] = useState<string | null>(null);

  const requests = useFmLoad("return-requests", (facility) => fetchReturnRequests(facility.id));
  const staff = useFmLoad("staff", (facility) => fetchFacilityStaff(facility.id));

  const all = requests.data ?? [];
  const list = all.filter((request) => request.status === tab);

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Yêu cầu trả kho</h1>
        <p className="mt-1.5 text-small text-muted">
          Phân công FS cho buổi trả kho — hệ thống đặt lịch ở khung trống đầu tiên từ ngày khách
          chọn. Phân công lại được khi khách chưa đến.
        </p>
      </div>

      <div className="flex flex-wrap gap-1.5" role="tablist" aria-label="Lọc theo trạng thái">
        {TABS.map((value) => {
          const active = tab === value;
          const count = all.filter((request) => request.status === value).length;
          return (
            <button
              key={value}
              role="tab"
              aria-selected={active}
              className={`inline-flex items-center gap-2 border-0 px-2.5 py-1.5 text-small font-semibold ${
                active
                  ? "bg-brand text-signal-ink"
                  : "bg-transparent text-muted hover:bg-surface-subtle hover:text-ink"
              }`}
              onClick={() => setTab(value)}
            >
              {STATUS_LABEL[value]}
              <span
                className={`px-1.5 text-label ${active ? "bg-signal-ink text-brand" : "bg-surface text-muted"}`}
              >
                {count}
              </span>
            </button>
          );
        })}
      </div>

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}
      {staff.error && (
        <DemoNotice tone="error">Không tải được danh sách FS: {staff.error.message}</DemoNotice>
      )}

      {requests.error ? (
        <SurfaceState
          {...loadErrorState(requests.error, "yêu cầu trả kho")}
          action={{ label: "Thử lại", onClick: requests.reload }}
        />
      ) : !requests.data ? (
        <SurfaceState variant="loading" title="Đang tải yêu cầu trả kho…" />
      ) : list.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={
            tab === "Pending"
              ? "Không có yêu cầu chờ phân công"
              : "Chưa có buổi trả kho đã phân công"
          }
        />
      ) : (
        <div className={`grid gap-2 ${requests.loading ? "opacity-60" : ""}`}>
          {list.map((request) => (
            <ReturnRow
              key={request.id}
              request={request}
              staff={staff.data ?? []}
              onAssigned={(result, member) => {
                setNotice(
                  `Đã phân công ${member ? staffLabel(member) : "FS"} cho buổi trả kho khoang ${
                    result.unitCode ?? "—"
                  }${result.appointmentStart ? ` lúc ${formatDateTime(result.appointmentStart)}` : ""}.`,
                );
                requests.reload();
              }}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function ReturnRow({
  request,
  staff,
  onAssigned,
}: {
  request: ReturnRequest;
  staff: FmStaff[];
  onAssigned: (result: ReturnRequest, member: FmStaff | undefined) => void;
}) {
  const [staffId, setStaffId] = useState("");
  const action = useFmAction();
  const assigned = staff.find((member) => member.id === request.assignedStaffId);
  const selectId = `return-staff-${request.id}`;

  async function assign() {
    const outcome = await action.run("assign", () =>
      assignReturnRequest(request.id, Number(staffId)),
    );
    if (outcome.ok) {
      onAssigned(
        outcome.value,
        staff.find((member) => member.id === Number(staffId)),
      );
    }
  }

  return (
    <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)_minmax(0,1.4fr)] items-start gap-4 bg-surface p-4 max-[900px]:grid-cols-1">
      <div className="min-w-0">
        <strong className="block text-small font-semibold text-ink">
          Khoang {request.unitCode || "—"}
        </strong>
        <span className="mt-0.5 block text-label text-muted">
          Hợp đồng #{request.contractId} · gửi {formatDateTime(request.createdAt)}
        </span>
        <span className="mt-1.5 inline-block">
          <StatusTag tone={toneOf(STATUS_TONE, request.status)}>
            {labelOf(STATUS_LABEL, request.status)}
          </StatusTag>
        </span>
      </div>
      <div className="min-w-0 text-small text-ink">
        <span className="flex items-center gap-2">
          <CalendarClock size={14} /> Khách muốn trả: {formatDate(request.preferredDate)}
        </span>
        {request.appointmentStart && (
          <span className="mt-1 block text-label text-muted">
            Lịch đã đặt: {formatTime(request.appointmentStart)}{" "}
            {formatDate(request.appointmentStart)}
          </span>
        )}
        {request.note && <p className="mt-1.5 text-label text-muted">Ghi chú: {request.note}</p>}
      </div>
      <div>
        {request.assignedStaffId !== null && (
          <span className="mb-2 flex items-center gap-2 text-small text-ink">
            <User size={14} /> {assigned ? staffLabel(assigned) : `FS #${request.assignedStaffId}`}
          </span>
        )}
        {request.appointmentArrivedAt !== null ? (
          // Buổi trả kho đã bắt đầu — backend từ chối (409) phân công lại.
          <span className="text-label text-muted">
            Khách đã đến lúc {formatDateTime(request.appointmentArrivedAt)} — không phân công lại.
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
              variant={request.assignedStaffId === null ? "primary" : "secondary"}
              pending={action.pending === "assign"}
              disabled={!staffId}
              onClick={assign}
            >
              {action.pending !== "assign" && <Check size={14} />}
              {request.assignedStaffId === null ? "Phân công" : "Phân công lại"}
            </Button>
          </div>
        )}
        {action.error && <DemoNotice tone="error">{action.error}</DemoNotice>}
      </div>
    </div>
  );
}
