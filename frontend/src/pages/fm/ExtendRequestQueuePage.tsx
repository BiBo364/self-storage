import { AlertTriangle, Check, X } from "lucide-react";
import { useState } from "react";
import { formatPrice } from "../../mocks/catalog";
import {
  extendRequests as initialRequests,
  type ExtendRequest,
  type ExtendRequestStatus,
} from "../../mocks/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

type StatusFilter = "all" | ExtendRequestStatus;

const STATUS_LABEL: Record<ExtendRequestStatus, string> = {
  PendingApproval: "Chờ duyệt",
  ApprovedPendingPayment: "Đã duyệt · chờ thanh toán",
  Rejected: "Đã từ chối",
};

const STATUS_TONE: Record<ExtendRequestStatus, "warning" | "success" | "danger"> = {
  PendingApproval: "warning",
  ApprovedPendingPayment: "success",
  Rejected: "danger",
};

function estimatedInvoice(request: ExtendRequest) {
  return request.extraMonths * request.monthlyPrice;
}

export function ExtendRequestQueuePage() {
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("PendingApproval");
  const [items, setItems] = useState<ExtendRequest[]>(initialRequests);
  const [openId, setOpenId] = useState<string | null>(null);
  const [mode, setMode] = useState<"view" | "reject">("view");
  const [rejectReason, setRejectReason] = useState("");
  const [busyId, setBusyId] = useState<string | null>(null);

  const list = items.filter((request) => statusFilter === "all" || request.status === statusFilter);
  const pendingCount = items.filter((request) => request.status === "PendingApproval").length;
  const openRequest = items.find((request) => request.id === openId) ?? null;

  function openDetails(request: ExtendRequest) {
    setOpenId(request.id);
    setMode("view");
    setRejectReason("");
  }

  function approve(request: ExtendRequest) {
    setBusyId(request.id);
    window.setTimeout(() => {
      setBusyId(null);
      setItems((current) =>
        current.map((item) =>
          item.id === request.id ? { ...item, status: "ApprovedPendingPayment" } : item,
        ),
      );
      setOpenId(null);
    }, 800);
  }

  function reject(request: ExtendRequest) {
    if (!rejectReason.trim()) return;
    setItems((current) =>
      current.map((item) =>
        item.id === request.id
          ? { ...item, status: "Rejected", rejectReason: rejectReason.trim() }
          : item,
      ),
    );
    setOpenId(null);
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Hàng chờ yêu cầu gia hạn</h1>
        <p className="mt-1.5 text-small text-muted">
          Yêu cầu gia hạn hợp đồng khách gửi — duyệt để tạo hoá đơn gia hạn, hoặc từ chối.
        </p>
      </div>

      <div className="max-w-[320px]">
        <FilterSelect
          label="Trạng thái"
          value={statusFilter}
          onChange={(value) => setStatusFilter(value as StatusFilter)}
        >
          <option value="all">Tất cả trạng thái</option>
          <option value="PendingApproval">
            Chờ duyệt{pendingCount > 0 ? ` (${pendingCount})` : ""}
          </option>
          <option value="ApprovedPendingPayment">Đã duyệt · chờ thanh toán</option>
          <option value="Rejected">Đã từ chối</option>
        </FilterSelect>
      </div>

      {list.length === 0 ? (
        <SurfaceState variant="empty" title="Không có yêu cầu nào phù hợp bộ lọc" />
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
                  Khoang {request.unitCode} · Gia hạn {request.extraMonths} tháng · Hết hạn hiện tại{" "}
                  {request.currentEndDate}
                </span>
                <span className="mt-1 block text-label text-muted">
                  Gửi lúc {request.requestedAt}
                </span>
              </div>
              <StatusTag tone={STATUS_TONE[request.status]}>
                {STATUS_LABEL[request.status]}
              </StatusTag>
            </button>
          ))}
        </div>
      )}

      {openRequest && (
        <DetailPanel
          title={openRequest.customerName}
          eyebrow={`Gia hạn · Khoang ${openRequest.unitCode}`}
          onClose={() => setOpenId(null)}
        >
          <div className="grid gap-2 text-small text-muted">
            <span>Hết hạn hiện tại: {openRequest.currentEndDate}</span>
            <span>Gia hạn thêm: {openRequest.extraMonths} tháng</span>
            <span>Giá thuê hiện tại: {formatPrice(openRequest.monthlyPrice)} đ / tháng</span>
          </div>

          <div className="mt-4 flex items-center justify-between gap-4 bg-surface-subtle p-3.5 text-small">
            <span className="text-muted">Hoá đơn gia hạn dự kiến</span>
            <strong className="font-semibold text-ink">
              {formatPrice(estimatedInvoice(openRequest))} đ
            </strong>
          </div>

          {openRequest.status === "PendingApproval" && mode === "view" && (
            <div className="mt-6 flex flex-wrap items-center gap-2.5">
              <Button pending={busyId === openRequest.id} onClick={() => approve(openRequest)}>
                {busyId !== openRequest.id && <Check size={16} />}
                Duyệt gia hạn
              </Button>
              <Button variant="secondary" onClick={() => setMode("reject")}>
                <X size={16} /> Từ chối
              </Button>
            </div>
          )}

          {openRequest.status === "PendingApproval" && mode === "reject" && (
            <div className="mt-6 bg-surface-subtle p-4">
              <label className="grid gap-1.5 text-label text-muted">
                Lý do từ chối
                <textarea
                  className="min-h-[80px] rounded-[2px] border border-rule-strong bg-surface p-2.5 text-small font-normal text-ink"
                  value={rejectReason}
                  onChange={(event) => setRejectReason(event.target.value)}
                  placeholder="Cho khách biết vì sao yêu cầu chưa được duyệt…"
                />
              </label>
              <div className="mt-3 flex items-center gap-2.5">
                <Button disabled={!rejectReason.trim()} onClick={() => reject(openRequest)}>
                  <AlertTriangle size={16} /> Xác nhận từ chối
                </Button>
                <button
                  className="border-0 bg-transparent px-3 py-2 text-small text-muted hover:text-ink"
                  onClick={() => setMode("view")}
                >
                  Quay lại
                </button>
              </div>
            </div>
          )}

          {openRequest.status === "ApprovedPendingPayment" && (
            <div className="mt-6">
              <DemoNotice tone="success">
                Đã duyệt — hệ thống tạo hoá đơn gia hạn, chờ khách thanh toán ở trang Hoá đơn.
              </DemoNotice>
            </div>
          )}
          {openRequest.status === "Rejected" && (
            <div className="mt-6">
              <DemoNotice tone="error">{openRequest.rejectReason}</DemoNotice>
            </div>
          )}
        </DetailPanel>
      )}
    </div>
  );
}
