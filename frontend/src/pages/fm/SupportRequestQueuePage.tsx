import { Check, ChevronRight, ImageIcon, User } from "lucide-react";
import { useState } from "react";
import { formatDateTime, formatMoney } from "../../app/format";
import { fetchFacilityStaff, type FmStaff } from "../../app/fmStaff";
import {
  assignSupportRequest,
  fetchSupportRequests,
  INVOICE_STATUS_LABEL,
  labelOf,
  loadErrorState,
  resolveSupportRequest,
  toneOf,
  useFmAction,
  useFmLoad,
  type SupportRequest,
} from "../../app/services/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FIELD_LABEL, INPUT_ELEMENT, INPUT_WRAP, SelectField } from "../../components/ui/FormField";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

type Tone = "success" | "warning" | "danger" | "info" | "neutral";

const STATUS_LABEL: Record<string, string> = {
  Open: "Đang mở",
  Assigned: "Đã giao FS",
  Resolved: "Đã xử lý",
  Canceled: "Khách đã huỷ",
};

const STATUS_TONE: Record<string, Tone> = {
  Open: "warning",
  Assigned: "info",
  Resolved: "success",
  Canceled: "neutral",
};

const CATEGORY_LABEL: Record<string, string> = {
  LOST_KEY: "Mất chìa khoá",
  ACCESS_CODE: "Mã ra vào",
  UNIT_ISSUE: "Sự cố khoang",
  BILLING: "Hoá đơn",
  OTHER: "Khác",
};

const OPEN_STATUSES = ["Open", "Assigned"];

function staffLabel(staff: FmStaff) {
  return staff.name ? `${staff.name} · ${staff.email}` : staff.email;
}

export function SupportRequestQueuePage() {
  const [includeClosed, setIncludeClosed] = useState(false);
  const [openId, setOpenId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const requests = useFmLoad(`support:${includeClosed}`, (facility) =>
    fetchSupportRequests(facility.id, includeClosed),
  );
  const staff = useFmLoad("staff", (facility) => fetchFacilityStaff(facility.id));

  const list = requests.data ?? [];
  const openRequest = list.find((request) => request.id === openId) ?? null;
  const openCount = list.filter((request) => OPEN_STATUSES.includes(request.status)).length;

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Yêu cầu hỗ trợ</h1>
        <p className="mt-1.5 text-small text-muted">
          {requests.data ? `${openCount} yêu cầu đang mở · ` : ""}Giao FS xử lý tại chỗ, hoặc ghi
          nhận kết quả trực tiếp.
        </p>
      </div>

      <label className="flex items-center gap-2 text-small text-ink">
        <input
          type="checkbox"
          checked={includeClosed}
          onChange={(event) => {
            setIncludeClosed(event.target.checked);
            setNotice(null);
          }}
        />
        Hiện cả yêu cầu đã xử lý / đã huỷ
      </label>

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}

      {requests.error ? (
        <SurfaceState
          {...loadErrorState(requests.error, "yêu cầu hỗ trợ")}
          action={{ label: "Thử lại", onClick: requests.reload }}
        />
      ) : !requests.data ? (
        <SurfaceState variant="loading" title="Đang tải yêu cầu hỗ trợ…" />
      ) : list.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Không có yêu cầu hỗ trợ"
          description={includeClosed ? undefined : "Bật “Hiện cả yêu cầu đã xử lý” để xem lịch sử."}
        />
      ) : (
        <div className={`grid gap-2 ${requests.loading ? "opacity-60" : ""}`}>
          {list.map((request) => (
            <button
              key={request.id}
              className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
              onClick={() => {
                setNotice(null);
                setOpenId(request.id);
              }}
            >
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <StatusTag tone={toneOf(STATUS_TONE, request.status)}>
                    {labelOf(STATUS_LABEL, request.status)}
                  </StatusTag>
                  <span className="text-label text-muted">
                    {labelOf(CATEGORY_LABEL, request.category)}
                  </span>
                </div>
                <strong className="mt-2 block text-small font-semibold text-ink">
                  {request.title}
                </strong>
                <span className="mt-0.5 block text-label text-muted">
                  {request.customerName ?? request.customerEmail ?? "—"}
                  {request.unitCode ? ` · Khoang ${request.unitCode}` : ""} · gửi{" "}
                  {formatDateTime(request.createdAt)}
                </span>
                {request.assignedStaffName && (
                  <span className="mt-1 flex items-center gap-1.5 text-label text-muted">
                    <User size={12} /> {request.assignedStaffName}
                  </span>
                )}
              </div>
              <ChevronRight size={16} className="mt-1 text-muted" />
            </button>
          ))}
        </div>
      )}

      {openRequest && (
        <SupportPanel
          request={openRequest}
          staff={staff.data ?? []}
          staffError={staff.error?.message ?? null}
          onClose={() => setOpenId(null)}
          onChanged={(message) => {
            setOpenId(null);
            setNotice(message);
            requests.reload();
          }}
        />
      )}
    </div>
  );
}

function SupportPanel({
  request,
  staff,
  staffError,
  onClose,
  onChanged,
}: {
  request: SupportRequest;
  staff: FmStaff[];
  staffError: string | null;
  onClose: () => void;
  onChanged: (message: string) => void;
}) {
  const [staffId, setStaffId] = useState("");
  const [resolution, setResolution] = useState("");
  const [keyQuantity, setKeyQuantity] = useState("");
  const [feeAmount, setFeeAmount] = useState("");
  const action = useFmAction();
  const isOpen = OPEN_STATUSES.includes(request.status);

  async function assign() {
    const outcome = await action.run("assign", () =>
      assignSupportRequest(request.id, Number(staffId)),
    );
    if (outcome.ok) {
      onChanged(
        `Đã giao yêu cầu “${request.title}” cho ${outcome.value.assignedStaffName ?? "FS"}.`,
      );
    }
  }

  async function resolve() {
    const outcome = await action.run("resolve", () =>
      resolveSupportRequest(request.id, {
        resolution: resolution.trim(),
        newKeyQuantity: keyQuantity ? Number(keyQuantity) : undefined,
        feeAmount: feeAmount ? Number(feeAmount) : undefined,
      }),
    );
    if (outcome.ok) onChanged(`Đã xử lý yêu cầu “${request.title}”.`);
  }

  return (
    <DetailPanel
      title={request.title}
      eyebrow={`${labelOf(CATEGORY_LABEL, request.category)} · ${labelOf(STATUS_LABEL, request.status)}`}
      onClose={onClose}
    >
      <div className="grid gap-5">
        <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-small">
          <Field label="Khách hàng" value={request.customerName} />
          <Field label="Email" value={request.customerEmail} />
          <Field label="Hợp đồng" value={request.contractCode} />
          <Field label="Khoang" value={request.unitCode} />
          <Field label="Gửi lúc" value={formatDateTime(request.createdAt)} />
          <Field label="FS phụ trách" value={request.assignedStaffName} />
        </dl>

        <p className="m-0 whitespace-pre-line text-small leading-relaxed text-ink">
          {request.description}
        </p>

        {request.photos && request.photos.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {request.photos.map((url, index) => (
              <a
                key={url}
                href={url}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1.5 bg-surface-subtle px-2.5 py-1.5 text-label text-accent hover:text-ink"
              >
                <ImageIcon size={13} /> Ảnh {index + 1}
              </a>
            ))}
          </div>
        )}

        {request.resolution && (
          <section>
            <SectionTitle>Kết quả xử lý</SectionTitle>
            <p className="mt-2 whitespace-pre-line text-small text-ink">{request.resolution}</p>
            <p className="mt-1 text-label text-muted">{formatDateTime(request.resolvedAt)}</p>
          </section>
        )}

        {request.feeInvoice && (
          <DemoNotice tone="info">
            Hoá đơn phí {request.feeInvoice.code} · {formatMoney(request.feeInvoice.amount)} ·{" "}
            {labelOf(INVOICE_STATUS_LABEL, request.feeInvoice.status)}
          </DemoNotice>
        )}

        {action.error && <DemoNotice tone="error">{action.error}</DemoNotice>}

        {isOpen && (
          <section className="bg-surface-subtle p-4">
            <SectionTitle>{request.assignedStaffId ? "Giao lại FS" : "Giao FS xử lý"}</SectionTitle>
            {staffError ? (
              <DemoNotice tone="error">Không tải được danh sách FS: {staffError}</DemoNotice>
            ) : staff.length === 0 ? (
              <p className="mt-2 text-label text-muted">Cơ sở chưa có FS để giao.</p>
            ) : (
              <div className="mt-3">
                <SelectField
                  label="Nhân viên FS"
                  name={`support-staff-${request.id}`}
                  value={staffId}
                  onChange={setStaffId}
                  placeholder="— Chọn FS —"
                  options={staff.map((member) => ({
                    value: String(member.id),
                    label: staffLabel(member),
                  }))}
                />
                <Button
                  variant="secondary"
                  pending={action.pending === "assign"}
                  disabled={!staffId}
                  onClick={assign}
                >
                  {action.pending !== "assign" && <User size={16} />} Giao FS
                </Button>
              </div>
            )}
          </section>
        )}

        {isOpen && (
          <section className="bg-surface-subtle p-4">
            <SectionTitle>Ghi nhận kết quả</SectionTitle>
            <label className={`${FIELD_LABEL} mt-3`} htmlFor={`resolution-${request.id}`}>
              Kết quả xử lý <span className="text-danger">*</span>
            </label>
            <div className={INPUT_WRAP}>
              <textarea
                id={`resolution-${request.id}`}
                className={`${INPUT_ELEMENT} min-h-[80px] py-2`}
                value={resolution}
                maxLength={2000}
                onChange={(event) => setResolution(event.target.value)}
              />
            </div>
            {request.contractId !== null && (
              <div className="mt-3 grid grid-cols-2 gap-3 max-[520px]:grid-cols-1">
                <div>
                  <label className={FIELD_LABEL} htmlFor={`keys-${request.id}`}>
                    Cấp bộ chìa mới (số chìa)
                  </label>
                  <div className={INPUT_WRAP}>
                    <input
                      id={`keys-${request.id}`}
                      type="number"
                      min={1}
                      max={10}
                      className={INPUT_ELEMENT}
                      value={keyQuantity}
                      onChange={(event) => setKeyQuantity(event.target.value)}
                      placeholder="Để trống nếu không thay"
                    />
                  </div>
                </div>
                <div>
                  <label className={FIELD_LABEL} htmlFor={`fee-${request.id}`}>
                    Phí dịch vụ
                  </label>
                  <div className={INPUT_WRAP}>
                    <input
                      id={`fee-${request.id}`}
                      type="number"
                      min={0}
                      step={1000}
                      className={INPUT_ELEMENT}
                      value={feeAmount}
                      onChange={(event) => setFeeAmount(event.target.value)}
                      placeholder="Để trống nếu không thu"
                    />
                    <span className="shrink-0 text-label text-muted">đ</span>
                  </div>
                </div>
              </div>
            )}
            <div className="mt-3">
              <Button
                pending={action.pending === "resolve"}
                disabled={!resolution.trim()}
                onClick={resolve}
              >
                {action.pending !== "resolve" && <Check size={16} />} Đánh dấu đã xử lý
              </Button>
            </div>
          </section>
        )}
      </div>
    </DetailPanel>
  );
}

function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div className="min-w-0">
      <dt className="text-label text-muted">{label}</dt>
      <dd className="m-0 break-words text-ink">{value || "—"}</dd>
    </div>
  );
}
