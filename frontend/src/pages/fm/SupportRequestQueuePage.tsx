import { Check } from "lucide-react";
import { useState } from "react";
import {
  fsStaff,
  supportRequests as initialRequests,
  type SupportIssueType,
  type SupportRequest,
  type SupportRequestStatus,
} from "../../mocks/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const TAB_LABEL: Record<SupportRequestStatus, string> = {
  Open: "Đang mở",
  Assigned: "Đã phân công",
};

const ISSUE_LABEL: Record<SupportIssueType, string> = {
  LostKey: "Mất chìa khoá",
  AccessCode: "Lỗi mã ra vào",
  UnitDamage: "Hư hỏng khoang",
  Other: "Khác",
};

const ISSUE_TONE: Record<SupportIssueType, "warning" | "info" | "danger" | "neutral"> = {
  LostKey: "warning",
  AccessCode: "info",
  UnitDamage: "danger",
  Other: "neutral",
};

function staffName(staffId: string | null) {
  return fsStaff.find((staff) => staff.id === staffId)?.name ?? null;
}

export function SupportRequestQueuePage() {
  const [items, setItems] = useState<SupportRequest[]>(initialRequests);
  const [tab, setTab] = useState<SupportRequestStatus>("Open");
  const [openId, setOpenId] = useState<string | null>(null);
  const [selectedStaffId, setSelectedStaffId] = useState("");
  const [busyId, setBusyId] = useState<string | null>(null);

  const list = items.filter((request) => request.status === tab);
  const openCount = items.filter((request) => request.status === "Open").length;
  const assignedCount = items.filter((request) => request.status === "Assigned").length;
  const openRequest = items.find((request) => request.id === openId) ?? null;

  function openDetails(request: SupportRequest) {
    setOpenId(request.id);
    setSelectedStaffId("");
  }

  function assign(request: SupportRequest) {
    if (!selectedStaffId) return;
    setBusyId(request.id);
    window.setTimeout(() => {
      setBusyId(null);
      setItems((current) =>
        current.map((item) =>
          item.id === request.id ? { ...item, status: "Assigned", staffId: selectedStaffId } : item,
        ),
      );
      setOpenId(null);
    }, 700);
  }

  const counts: Record<SupportRequestStatus, number> = {
    Open: openCount,
    Assigned: assignedCount,
  };

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Hàng chờ yêu cầu hỗ trợ</h1>
        <p className="mt-1.5 text-small text-muted">
          Sự cố khách báo hoặc FS tự ghi nhận tại kho — phân công FS xử lý.
        </p>
      </div>

      <div className="flex flex-wrap gap-1.5">
        {(["Open", "Assigned"] as const).map((status) => (
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
                <div className="flex flex-wrap items-center gap-2">
                  <StatusTag tone={ISSUE_TONE[request.issueType]}>
                    {ISSUE_LABEL[request.issueType]}
                  </StatusTag>
                  <strong className="text-small font-semibold text-ink">
                    Khoang {request.unitCode}
                  </strong>
                </div>
                <span className="mt-1.5 block text-label text-muted">
                  {request.reporterName} ·{" "}
                  {request.reporterRole === "Customer" ? "Khách thuê" : "Nhân viên FS"}
                </span>
              </div>
              <StatusTag tone={request.status === "Assigned" ? "success" : "warning"}>
                {request.status === "Assigned"
                  ? `FS: ${staffName(request.staffId)}`
                  : "Chưa phân công"}
              </StatusTag>
            </button>
          ))}
        </div>
      )}

      {openRequest && (
        <DetailPanel
          title={`Khoang ${openRequest.unitCode}`}
          eyebrow={ISSUE_LABEL[openRequest.issueType]}
          onClose={() => setOpenId(null)}
        >
          <div className="grid gap-2 text-small text-muted">
            <span>
              Người báo: {openRequest.reporterName} (
              {openRequest.reporterRole === "Customer" ? "Khách thuê" : "Nhân viên FS"})
            </span>
            <span>
              Hợp đồng liên quan: {openRequest.contractId ?? "Không có hợp đồng liên quan"}
            </span>
          </div>
          <p className="mt-4 text-small leading-relaxed text-ink">{openRequest.description}</p>

          {openRequest.status === "Assigned" ? (
            <div className="mt-6">
              <DemoNotice tone="success">
                Đã phân công {staffName(openRequest.staffId)} xử lý sự cố này.
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
