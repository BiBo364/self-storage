import { Check } from "lucide-react";
import { useState } from "react";
import {
  fsStaff,
  recordReturnAppointment,
  returnRequests as initialRequests,
  type ReturnRequest,
  type ReturnRequestStatus,
} from "../../mocks/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const TAB_LABEL: Record<ReturnRequestStatus, string> = {
  Pending: "Chờ phân công",
  Assigned: "Đã phân công",
};

function staffName(staffId: string | null) {
  return fsStaff.find((staff) => staff.id === staffId)?.name ?? null;
}

export function ReturnRequestQueuePage() {
  const [items, setItems] = useState<ReturnRequest[]>(initialRequests);
  const [tab, setTab] = useState<ReturnRequestStatus>("Pending");
  const [openId, setOpenId] = useState<string | null>(null);
  const [selectedStaffId, setSelectedStaffId] = useState("");
  const [busyId, setBusyId] = useState<string | null>(null);

  const list = items.filter((request) => request.status === tab);
  const pendingCount = items.filter((request) => request.status === "Pending").length;
  const assignedCount = items.filter((request) => request.status === "Assigned").length;
  const openRequest = items.find((request) => request.id === openId) ?? null;

  function openDetails(request: ReturnRequest) {
    setOpenId(request.id);
    setSelectedStaffId("");
  }

  function assign(request: ReturnRequest) {
    if (!selectedStaffId) return;
    setBusyId(request.id);
    window.setTimeout(() => {
      setBusyId(null);
      setItems((current) =>
        current.map((item) =>
          item.id === request.id ? { ...item, status: "Assigned", staffId: selectedStaffId } : item,
        ),
      );
      recordReturnAppointment(request, selectedStaffId);
      setOpenId(null);
    }, 700);
  }

  const counts: Record<ReturnRequestStatus, number> = {
    Pending: pendingCount,
    Assigned: assignedCount,
  };

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Hàng chờ yêu cầu trả kho</h1>
        <p className="mt-1.5 text-small text-muted">
          Yêu cầu trả kho khách gửi từ trang chi tiết hợp đồng — phân công FS phụ trách.
        </p>
      </div>

      <DemoNotice tone="pending">
        Sau khi phân công, hệ thống tự tạo lịch hẹn trả kho ở trang{" "}
        <strong>Lịch hẹn &amp; phân công FS</strong> — không cần thao tác gì thêm ở đó.
      </DemoNotice>

      <div className="flex flex-wrap gap-1.5">
        {(["Pending", "Assigned"] as const).map((status) => (
          <button
            key={status}
            className={`inline-flex items-center gap-2 border-0 px-2.5 py-1.5 text-small font-semibold ${
              tab === status
                ? "bg-brand text-signal-ink"
                : "bg-transparent text-muted hover:bg-surface-subtle hover:text-ink"
            }`}
            onClick={() => setTab(status)}
          >
            {TAB_LABEL[status]}
            <span
              className={`px-1.5 text-label ${
                tab === status ? "bg-signal-ink text-brand" : "bg-surface text-muted"
              }`}
            >
              {counts[status]}
            </span>
          </button>
        ))}
      </div>

      {list.length === 0 ? (
        <SurfaceState variant="empty" title="Không có yêu cầu nào trong mục này" />
      ) : (
        <div className="grid gap-2">
          {list.map((request) => (
            <button
              key={request.id}
              className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
              onClick={() => openDetails(request)}
            >
              <div>
                <strong className="block text-small font-semibold text-ink">
                  {request.customerName}
                </strong>
                <span className="mt-1 block text-label text-muted">
                  Khoang {request.unitCode} · Mong muốn trả ngày {request.preferredDate}
                </span>
              </div>
              <StatusTag tone={request.status === "Assigned" ? "success" : "warning"}>
                {request.status === "Assigned"
                  ? `FS: ${staffName(request.staffId)}`
                  : "Chờ phân công"}
              </StatusTag>
            </button>
          ))}
        </div>
      )}

      {openRequest && (
        <DetailPanel
          title={openRequest.customerName}
          eyebrow={`Trả kho · ${openRequest.unitCode}`}
          onClose={() => setOpenId(null)}
        >
          <p className="text-small text-muted">Mong muốn trả ngày {openRequest.preferredDate}</p>
          {openRequest.reason && (
            <p className="mt-4 text-small leading-relaxed text-ink">{openRequest.reason}</p>
          )}

          {openRequest.status === "Assigned" ? (
            <div className="mt-6">
              <DemoNotice tone="success">
                Đã phân công {staffName(openRequest.staffId)} phụ trách — lịch hẹn trả kho đã được
                tạo tự động.
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
                  pending={busyId === openRequest.id}
                  disabled={!selectedStaffId}
                  onClick={() => assign(openRequest)}
                >
                  {busyId !== openRequest.id && <Check size={16} />}
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
