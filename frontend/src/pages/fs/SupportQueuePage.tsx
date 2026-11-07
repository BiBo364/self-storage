import { ChevronRight, MapPin } from "lucide-react";
import { useEffect, useState } from "react";
import { formatDateTime, formatMoney } from "../../app/format";
import { errorMessage, errorStatus } from "../../app/http";
import {
  fetchStaffSupportRequests,
  resolveSupportRequest,
  type SupportView,
} from "../../app/services/staff";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FIELD_LABEL } from "../../components/ui/FormField";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";
import { INPUT_CLASS, TEXTAREA_CLASS } from "../../components/domain/FileUploadField";

const CATEGORY_LABEL: Record<string, string> = {
  LOST_KEY: "Mất chìa khoá",
  ACCESS_CODE: "Mã truy cập",
  UNIT_ISSUE: "Sự cố khoang",
  BILLING: "Thanh toán",
  OTHER: "Khác",
};

const STATUS: Record<string, { label: string; tone: "success" | "warning" | "neutral" | "info" }> =
  {
    Open: { label: "Mới", tone: "warning" },
    Assigned: { label: "Đã giao", tone: "info" },
    Resolved: { label: "Đã xử lý", tone: "success" },
    Canceled: { label: "Đã huỷ", tone: "neutral" },
  };

function statusOf(status: string) {
  return STATUS[status] ?? { label: status, tone: "neutral" as const };
}

/** FS — yêu cầu hỗ trợ được giao. */
export function StaffSupportPage({ navigate }: { navigate: Navigate }) {
  const [requests, setRequests] = useState<SupportView[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const list = await fetchStaffSupportRequests();
        if (active) setRequests(list);
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [reloadKey]);

  function reload() {
    setIsLoading(true);
    setError(null);
    setReloadKey((key) => key + 1);
  }

  const openRequest = requests.find((request) => request.id === openId) ?? null;

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Yêu cầu hỗ trợ</h1>
        <p className="mt-1.5 text-small text-muted">
          Yêu cầu FM đã giao cho bạn. Ghi kết quả xử lý, thay chìa hoặc thu phí dịch vụ nếu cần.
        </p>
      </div>

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải yêu cầu hỗ trợ…" />
      ) : error ? (
        error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xem yêu cầu hỗ trợ"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được yêu cầu hỗ trợ"
            description={error.message}
            action={{ label: "Thử lại", onClick: reload }}
          />
        )
      ) : requests.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Không có yêu cầu hỗ trợ nào đang chờ bạn"
          description="Yêu cầu FM giao cho bạn sẽ hiện ở đây."
          action={{ label: "Mở lịch làm việc", onClick: () => navigate("/staff/schedule") }}
        />
      ) : (
        <div className="grid gap-2">
          {requests.map((request) => {
            const status = statusOf(request.status);
            return (
              <button
                key={request.id}
                className="flex flex-wrap items-center justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
                onClick={() => {
                  setNotice(null);
                  setOpenId(request.id);
                }}
              >
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <StatusTag tone={status.tone}>{status.label}</StatusTag>
                    <span className="bg-surface-subtle px-2 py-1 text-label font-semibold text-muted">
                      {CATEGORY_LABEL[request.category] ?? request.category}
                    </span>
                  </div>
                  <strong className="mt-2 block text-section text-ink">{request.title}</strong>
                  <span className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-small text-muted">
                    <span>{request.customerName ?? request.customerEmail ?? "—"}</span>
                    {request.unitCode && (
                      <span className="flex items-center gap-1.5">
                        <MapPin size={14} /> {request.unitCode}
                      </span>
                    )}
                    <span>Giao lúc {formatDateTime(request.assignedAt)}</span>
                  </span>
                </div>
                <ChevronRight size={18} className="text-muted" />
              </button>
            );
          })}
        </div>
      )}

      {openRequest && (
        <ResolvePanel
          key={openRequest.id}
          request={openRequest}
          onClose={() => setOpenId(null)}
          onResolved={(resolved) => {
            setOpenId(null);
            setNotice(`Đã xử lý yêu cầu “${resolved.title}”.`);
            setRequests((list) => list.filter((item) => item.id !== resolved.id));
          }}
        />
      )}
    </div>
  );
}

function ResolvePanel({
  request,
  onClose,
  onResolved,
}: {
  request: SupportView;
  onClose: () => void;
  onResolved: (resolved: SupportView) => void;
}) {
  const [resolution, setResolution] = useState("");
  const [newKeyQuantity, setNewKeyQuantity] = useState("");
  const [feeAmount, setFeeAmount] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!resolution.trim()) {
      setError("Vui lòng nhập kết quả xử lý.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const resolved = await resolveSupportRequest(request.id, {
        resolution: resolution.trim(),
        newKeyQuantity: newKeyQuantity.trim() === "" ? undefined : Number(newKeyQuantity),
        feeAmount: feeAmount.trim() === "" ? undefined : Number(feeAmount),
      });
      onResolved(resolved);
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(false);
    }
  }

  return (
    <DetailPanel
      title={request.title}
      eyebrow={`${CATEGORY_LABEL[request.category] ?? request.category} · #${request.id}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>
            Đóng
          </Button>
          <Button pending={busy} onClick={submit}>
            Đánh dấu đã xử lý
          </Button>
        </>
      }
    >
      <dl className="m-0 grid gap-2 text-small">
        <div className="flex justify-between gap-3">
          <dt className="text-muted">Khách hàng</dt>
          <dd className="m-0 text-right text-ink">
            {request.customerName ?? "—"}
            {request.customerEmail ? ` · ${request.customerEmail}` : ""}
          </dd>
        </div>
        <div className="flex justify-between gap-3">
          <dt className="text-muted">Cơ sở</dt>
          <dd className="m-0 text-right text-ink">{request.facilityName ?? "—"}</dd>
        </div>
        <div className="flex justify-between gap-3">
          <dt className="text-muted">Hợp đồng</dt>
          <dd className="m-0 text-right text-ink">
            {request.contractCode ?? "—"}
            {request.unitCode ? ` · ${request.unitCode}` : ""}
          </dd>
        </div>
        <div className="flex justify-between gap-3">
          <dt className="text-muted">Gửi lúc</dt>
          <dd className="m-0 text-right text-ink">{formatDateTime(request.createdAt)}</dd>
        </div>
      </dl>

      <p className="mt-4 whitespace-pre-line bg-surface-subtle p-4 text-small text-ink">
        {request.description}
      </p>

      {(request.photos?.length ?? 0) > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {request.photos?.map((url) => (
            <a
              key={url}
              href={url}
              target="_blank"
              rel="noreferrer"
              className="block h-20 w-20 border border-border"
            >
              {url.toLowerCase().endsWith(".pdf") ? (
                <span className="grid h-full w-full place-items-center bg-surface text-label text-muted">
                  PDF
                </span>
              ) : (
                <img src={url} alt="Ảnh khách gửi" className="h-full w-full object-cover" />
              )}
            </a>
          ))}
        </div>
      )}

      {request.feeInvoice && (
        <p className="mt-3 text-small text-muted">
          Hoá đơn phí: {request.feeInvoice.code} · {formatMoney(request.feeInvoice.amount)}
        </p>
      )}

      {error && <DemoNotice tone="error">{error}</DemoNotice>}

      <div className="mt-5 grid gap-4">
        <div>
          <label className={FIELD_LABEL} htmlFor="resolution">
            Kết quả xử lý <span className="text-danger">*</span>
          </label>
          <textarea
            id="resolution"
            className={TEXTAREA_CLASS}
            value={resolution}
            maxLength={2000}
            onChange={(event) => setResolution(event.target.value)}
            placeholder="Ví dụ: Đã thay ổ khoá, giao 2 chìa mới cho khách."
          />
        </div>
        {request.contractId !== null && (
          <>
            <div>
              <label className={FIELD_LABEL} htmlFor="new-key-quantity">
                Cấp chìa mới (số lượng, để trống nếu không thay)
              </label>
              <input
                id="new-key-quantity"
                type="number"
                min={1}
                max={10}
                className={INPUT_CLASS}
                value={newKeyQuantity}
                onChange={(event) => setNewKeyQuantity(event.target.value)}
              />
            </div>
            <div>
              <label className={FIELD_LABEL} htmlFor="fee-amount">
                Phí dịch vụ (đ, để trống nếu không thu)
              </label>
              <input
                id="fee-amount"
                type="number"
                min={0}
                step={1000}
                className={INPUT_CLASS}
                value={feeAmount}
                onChange={(event) => setFeeAmount(event.target.value)}
              />
            </div>
          </>
        )}
      </div>
    </DetailPanel>
  );
}
