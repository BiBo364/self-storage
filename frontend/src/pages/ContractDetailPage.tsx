import {
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
  Download,
  FileWarning,
  MapPin,
  X,
} from "lucide-react";
import { useState } from "react";
import type { Navigate } from "../app/types";
import {
  type Contract,
  contracts,
  type ExtendRequest,
  type ReturnRequest,
  SUPPORT_ISSUE_LABEL,
  type SupportIssueType,
  type SupportRequest,
} from "../mocks/contracts";
import { formatPrice } from "../mocks/catalog";
import { invoices } from "../mocks/invoices";
import { Button, buttonClassName } from "../components/ui/Button";
import { DemoNotice } from "../components/ui/DemoNotice";
import { DetailPanel } from "../components/ui/DetailPanel";
import { SectionTitle } from "../components/ui/SectionTitle";
import { SurfaceState } from "../components/ui/SurfaceState";

const SUPPORT_STATUS_LABEL: Record<SupportRequest["status"], string> = {
  Open: "Mới gửi",
  Assigned: "Đã phân công",
  InProgress: "Đang xử lý",
  Resolved: "Đã xử lý",
  Closed: "Đã đóng",
};

const SUPPORT_STATUS_CLASS: Record<SupportRequest["status"], string> = {
  Open: "bg-warning-soft text-warning",
  Assigned: "bg-info-soft text-info",
  InProgress: "bg-info-soft text-info",
  Resolved: "bg-success-soft text-success",
  Closed: "bg-surface-subtle text-muted",
};

function getContractId() {
  const segments = window.location.pathname.split("/").filter(Boolean);
  return segments[segments.length - 1] ?? "";
}

export function ContractDetailPage({ navigate }: { navigate: Navigate }) {
  const id = getContractId();
  const initial = contracts[id];

  const [contract, setContract] = useState<Contract | undefined>(initial);
  const [activePanel, setActivePanel] = useState<"extend" | "return" | "support" | null>(null);

  if (!contract) {
    return (
      <SurfaceState
        variant="not-found"
        title="Không tìm thấy hợp đồng"
        description="Đường dẫn không đúng hoặc hợp đồng không thuộc tài khoản của bạn."
        action={{ label: "Về Kho của tôi", onClick: () => navigate("/my-storage") }}
      />
    );
  }

  const contractInvoices = invoices.filter((invoice) => invoice.unitCode === contract.unitCode);
  const status = contract.isOverdue
    ? { label: "Quá hạn", className: "bg-danger-soft text-danger" }
    : contract.isExpiringSoon
      ? { label: "Sắp hết hạn", className: "bg-warning-soft text-warning" }
      : { label: "Đang hiệu lực", className: "bg-success-soft text-success" };

  function updateContract(patch: Partial<Contract>) {
    setContract((current) => (current ? { ...current, ...patch } : current));
  }

  return (
    <div className="grid gap-6">
      <button
        className="inline-flex w-fit items-center gap-1.5 border-0 bg-transparent p-0 text-small font-semibold text-accent hover:text-ink"
        onClick={() => navigate("/my-storage")}
      >
        <ArrowLeft size={16} /> Về Kho của tôi
      </button>

      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <span className="text-label text-muted">
            Khoang {contract.unitCode} · {contract.unitType}
          </span>
          <h1 className="mt-1 text-title text-ink">{contract.facility}</h1>
          <p className="mt-1.5 flex items-center gap-1.5 text-small text-muted">
            <MapPin size={15} /> {contract.district} · {contract.size}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={`inline-flex items-center gap-1.5 px-2 py-1 text-label font-semibold ${status.className}`}
          >
            <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />
            {status.label}
          </span>
          {contract.pdfUrl && (
            <a href={contract.pdfUrl} className={buttonClassName("secondary")}>
              <Download size={16} /> Tải hợp đồng
            </a>
          )}
        </div>
      </div>

      <div className="grid grid-cols-4 gap-2 max-[980px]:grid-cols-2 max-[760px]:grid-cols-1">
        <Summary label="Khoang" value={`${contract.unitCode} · ${contract.size}`} />
        <Summary label="Giá thuê / tháng" value={`${formatPrice(contract.monthlyPrice)} đ`} />
        <Summary label="Tiền cọc" value={`${formatPrice(contract.depositAmount)} đ`} />
        <Summary label="Hiệu lực" value={`${contract.startDate} → ${contract.endDate}`} />
      </div>

      {contract.isOverdue && (
        <DemoNotice tone="error">
          Hợp đồng đã quá hạn. Không thể gia hạn qua web — vui lòng liên hệ trực tiếp quản lý cơ sở
          (FM).
        </DemoNotice>
      )}
      {!contract.isOverdue && contract.isExpiringSoon && (
        <DemoNotice tone="pending">
          Hợp đồng sắp hết hạn ({contract.endDate}). Gửi yêu cầu gia hạn sớm để không bị gián đoạn.
        </DemoNotice>
      )}

      <div className="grid grid-cols-[minmax(0,1fr)_320px] items-start gap-6 max-[980px]:grid-cols-1">
        <div className="grid gap-3">
          <section className="bg-surface p-5">
            <SectionTitle>Thông tin hợp đồng</SectionTitle>
            <dl className="mt-4 grid grid-cols-2 gap-4 max-[760px]:grid-cols-1">
              <Field label="Ngày ký" value={contract.signedAt} />
              <Field label="Hiệu lực" value={`${contract.startDate} — ${contract.endDate}`} />
              <Field label="Tiền cọc" value={`${formatPrice(contract.depositAmount)} đ`} />
              <Field label="Giá thuê / tháng" value={`${formatPrice(contract.monthlyPrice)} đ`} />
            </dl>
          </section>

          <section className="bg-surface p-5">
            <SectionTitle
              action={
                <button
                  className="border-0 bg-transparent p-0 text-small font-semibold text-accent hover:text-ink"
                  onClick={() => navigate("/invoices")}
                >
                  Xem tất cả
                </button>
              }
            >
              Hoá đơn
            </SectionTitle>
            {contractInvoices.length === 0 ? (
              <p className="mt-3 text-small text-muted">Chưa có hoá đơn nào.</p>
            ) : (
              <div className="mt-3 grid gap-2">
                {contractInvoices.map((invoice) => (
                  <div
                    key={invoice.id}
                    className="flex items-center justify-between gap-4 bg-surface-subtle px-3.5 py-3"
                  >
                    <span className="text-small text-ink">{invoice.title}</span>
                    <strong className="text-small font-semibold text-ink">
                      {formatPrice(invoice.amount)} đ
                    </strong>
                  </div>
                ))}
              </div>
            )}
          </section>

          <section className="bg-surface p-5">
            <SectionTitle>Lịch sử bàn giao</SectionTitle>
            <div className="mt-3 grid gap-2">
              {contract.handoverHistory.map((event) => (
                <div key={event.id} className="flex gap-3 bg-surface-subtle p-3.5">
                  <span className="mt-1.5 h-2 w-2 shrink-0 bg-brand" aria-hidden="true" />
                  <div>
                    <p className="text-small font-semibold text-ink">
                      {event.label} · {event.date}
                    </p>
                    <p className="mt-1 text-label text-muted">{event.note}</p>
                  </div>
                </div>
              ))}
            </div>
          </section>

          <section className="bg-surface p-5">
            <SectionTitle>Yêu cầu hỗ trợ</SectionTitle>
            {contract.supportRequests.length === 0 ? (
              <p className="mt-3 text-small text-muted">Chưa có yêu cầu hỗ trợ nào.</p>
            ) : (
              <div className="mt-3 grid gap-2">
                {contract.supportRequests.map((request) => (
                  <div
                    key={request.id}
                    className="flex items-center justify-between gap-4 bg-surface-subtle px-3.5 py-3"
                  >
                    <div>
                      <p className="text-small font-semibold text-ink">
                        {SUPPORT_ISSUE_LABEL[request.issueType]}
                      </p>
                      <p className="mt-0.5 text-label text-muted">{request.createdAt}</p>
                    </div>
                    <span
                      className={`inline-flex items-center gap-1.5 px-2 py-1 text-label font-semibold ${SUPPORT_STATUS_CLASS[request.status]}`}
                    >
                      <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />
                      {SUPPORT_STATUS_LABEL[request.status]}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </section>
        </div>

        <aside className="grid gap-3">
          <section className="bg-surface p-5">
            <SectionTitle>Hành động</SectionTitle>
            <div className="mt-4 grid gap-2">
              {contract.openExtendRequest ? (
                <ExtendStatusCard
                  request={contract.openExtendRequest}
                  onCancel={() => updateContract({ openExtendRequest: null })}
                  onPay={() => navigate("/invoices")}
                />
              ) : (
                !contract.openReturnRequest &&
                contract.availableActions.includes("extend") && (
                  <Button className="w-full" onClick={() => setActivePanel("extend")}>
                    Yêu cầu gia hạn
                  </Button>
                )
              )}

              {contract.openReturnRequest ? (
                <ReturnStatusCard
                  request={contract.openReturnRequest}
                  onCancel={() => updateContract({ openReturnRequest: null })}
                />
              ) : (
                !contract.openExtendRequest &&
                contract.availableActions.includes("return") && (
                  <Button
                    variant="secondary"
                    className="w-full"
                    onClick={() => setActivePanel("return")}
                  >
                    Yêu cầu trả kho
                  </Button>
                )
              )}

              {contract.availableActions.includes("report-issue") && (
                <button
                  className="inline-flex min-h-[38px] w-full items-center justify-center gap-2 border-0 bg-transparent text-small font-semibold text-muted hover:text-accent"
                  onClick={() => setActivePanel("support")}
                >
                  <FileWarning size={16} /> Báo sự cố
                </button>
              )}
            </div>
          </section>
        </aside>
      </div>

      {activePanel === "extend" && (
        <ExtendPanel
          contract={contract}
          onClose={() => setActivePanel(null)}
          onSubmit={(request) => {
            updateContract({ openExtendRequest: request });
            setActivePanel(null);
          }}
        />
      )}
      {activePanel === "return" && (
        <ReturnPanel
          onClose={() => setActivePanel(null)}
          onSubmit={(request) => {
            updateContract({ openReturnRequest: request });
            setActivePanel(null);
          }}
        />
      )}
      {activePanel === "support" && (
        <SupportPanel
          onClose={() => setActivePanel(null)}
          onSubmit={(request) => {
            updateContract({ supportRequests: [request, ...contract.supportRequests] });
            setActivePanel(null);
          }}
        />
      )}
    </div>
  );
}

function Summary({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-surface p-4">
      <span className="text-label text-muted">{label}</span>
      <strong className="mt-1 block text-[18px] font-semibold text-ink">{value}</strong>
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid gap-0.5">
      <span className="text-label text-muted">{label}</span>
      <strong className="text-small font-semibold text-ink">{value}</strong>
    </div>
  );
}

function ExtendStatusCard({
  request,
  onCancel,
  onPay,
}: {
  request: ExtendRequest;
  onCancel: () => void;
  onPay: () => void;
}) {
  if (request.status === "PendingApproval") {
    return (
      <div className="bg-surface-subtle p-4">
        <DemoNotice tone="pending">Đang chờ FM duyệt gia hạn.</DemoNotice>
        <Button variant="secondary" className="mt-3 w-full" onClick={onCancel}>
          <X size={16} /> Huỷ yêu cầu
        </Button>
      </div>
    );
  }
  if (request.status === "ApprovedPendingPayment") {
    return (
      <div className="bg-surface-subtle p-4">
        <p className="text-small font-semibold text-ink">Yêu cầu đã được duyệt</p>
        <p className="mt-1 text-small text-muted">
          Thêm {request.extraMonths} tháng · {formatPrice(request.amount ?? 0)}đ · hạn thanh toán{" "}
          {request.dueDate}
        </p>
        <p className="mt-2 text-label text-muted">
          Không thể huỷ qua web — liên hệ FM trực tiếp nếu cần huỷ.
        </p>
        <Button className="mt-3 w-full" onClick={onPay}>
          Thanh toán gia hạn <ArrowRight size={16} />
        </Button>
      </div>
    );
  }
  return null;
}

function ReturnStatusCard({ request, onCancel }: { request: ReturnRequest; onCancel: () => void }) {
  if (request.status === "Assigned") {
    return (
      <div className="bg-surface-subtle p-4">
        <DemoNotice tone="success">
          Đã phân công nhân viên, ngày hẹn dự kiến {request.preferredDate}.
        </DemoNotice>
        <p className="mt-2 text-label text-muted">
          Không thể huỷ qua web — liên hệ FM/FS trực tiếp nếu cần đổi.
        </p>
      </div>
    );
  }
  return (
    <div className="bg-surface-subtle p-4">
      <DemoNotice tone="pending">Đang chờ FM phân công nhân viên xử lý.</DemoNotice>
      <Button variant="secondary" className="mt-3 w-full" onClick={onCancel}>
        <X size={16} /> Huỷ yêu cầu
      </Button>
    </div>
  );
}

function ExtendPanel({
  contract,
  onClose,
  onSubmit,
}: {
  contract: Contract;
  onClose: () => void;
  onSubmit: (request: ExtendRequest) => void;
}) {
  const [months, setMonths] = useState(1);

  return (
    <DetailPanel title="Yêu cầu gia hạn hợp đồng" onClose={onClose}>
      <p className="text-small text-muted">
        Chọn số tháng muốn gia hạn thêm cho khoang {contract.unitCode}. Đội ngũ vận hành sẽ duyệt
        trước khi bạn thanh toán.
      </p>
      <label className="mt-5 grid gap-1.5 text-label text-muted">
        Số tháng gia hạn
        <input
          type="number"
          min={1}
          max={12}
          value={months}
          onChange={(event) => setMonths(Number(event.target.value) || 1)}
          className="w-24 rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink"
        />
      </label>
      <p className="mt-3 text-label text-muted">
        Ước tính: {formatPrice(months * contract.monthlyPrice)}đ (chốt chính thức sau khi FM duyệt).
      </p>
      <Button
        className="mt-6 w-full"
        onClick={() => onSubmit({ status: "PendingApproval", extraMonths: months })}
      >
        Gửi yêu cầu gia hạn
      </Button>
    </DetailPanel>
  );
}

function ReturnPanel({
  onClose,
  onSubmit,
}: {
  onClose: () => void;
  onSubmit: (request: ReturnRequest) => void;
}) {
  const [date, setDate] = useState("");
  const [reason, setReason] = useState("");

  return (
    <DetailPanel title="Yêu cầu trả kho" onClose={onClose}>
      <label className="grid gap-1.5 text-label text-muted">
        Ngày mong muốn trả kho
        <input
          type="date"
          min={new Date().toISOString().slice(0, 10)}
          value={date}
          onChange={(event) => setDate(event.target.value)}
          className="w-fit rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink"
        />
      </label>
      <label className="mt-5 grid gap-1.5 text-label text-muted">
        Lý do (không bắt buộc)
        <textarea
          className="min-h-[70px] rounded-[2px] border border-rule-strong bg-surface p-2.5 text-small font-normal text-ink"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
      </label>
      <Button
        className="mt-6 w-full"
        disabled={!date}
        onClick={() =>
          onSubmit({ status: "Pending", preferredDate: date, reason: reason || undefined })
        }
      >
        Gửi yêu cầu trả kho
      </Button>
    </DetailPanel>
  );
}

function SupportPanel({
  onClose,
  onSubmit,
}: {
  onClose: () => void;
  onSubmit: (request: SupportRequest) => void;
}) {
  const [issueType, setIssueType] = useState<SupportIssueType>("LostKey");
  const [description, setDescription] = useState("");

  return (
    <DetailPanel title="Báo sự cố" onClose={onClose}>
      <div className="mb-4">
        <DemoNotice tone="info">
          Bạn có thể gửi báo sự cố bất cứ lúc nào, kể cả khi hợp đồng quá hạn hoặc đang có yêu cầu
          khác chờ xử lý.
        </DemoNotice>
      </div>
      <label className="grid gap-1.5 text-label text-muted">
        Loại sự cố
        <select
          className="rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink"
          value={issueType}
          onChange={(event) => setIssueType(event.target.value as SupportIssueType)}
        >
          {Object.entries(SUPPORT_ISSUE_LABEL).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </label>
      <label className="mt-5 grid gap-1.5 text-label text-muted">
        Mô tả chi tiết
        <textarea
          className="min-h-[90px] rounded-[2px] border border-rule-strong bg-surface p-2.5 text-small font-normal text-ink"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          placeholder="Mô tả sự cố để đội ngũ hỗ trợ xử lý nhanh hơn…"
        />
      </label>
      <Button
        className="mt-6 w-full"
        disabled={!description.trim()}
        onClick={() =>
          onSubmit({
            id: `support-${Date.now()}`,
            issueType,
            description,
            status: "Open",
            createdAt: new Date().toLocaleDateString("vi-VN"),
          })
        }
      >
        <AlertTriangle size={16} /> Gửi báo sự cố
      </Button>
    </DetailPanel>
  );
}
