import type { ReactNode } from "react";
import { formatDateTime } from "../../app/format";
import {
  ACCOUNT_REQUEST_ROLE_LABEL,
  ACCOUNT_REQUEST_STATUS_LABEL,
  type AccountRequestRow,
  type AccountRequestStatus,
} from "../../app/services/bom";
import { StatusTag } from "../ui/StatusTag";

const STATUS_TONE: Record<AccountRequestStatus, "warning" | "success" | "danger" | "neutral"> = {
  Pending: "warning",
  Approved: "success",
  Rejected: "danger",
  Canceled: "neutral",
};

/** Một yêu cầu tạo tài khoản (BOM gửi, ADMIN duyệt) — dùng ở cả trang BOM và trang ADMIN; `actions` là nút thao tác. */
export function AccountRequestCard({
  request,
  actions,
}: {
  request: AccountRequestRow;
  actions?: ReactNode;
}) {
  return (
    <article className="grid gap-3 bg-surface p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <strong className="text-body font-semibold text-ink">{request.name}</strong>
          <span className="mt-0.5 block text-small text-muted">
            {request.email}
            {request.phone ? ` · ${request.phone}` : ""}
          </span>
        </div>
        <StatusTag tone={STATUS_TONE[request.status]}>
          {ACCOUNT_REQUEST_STATUS_LABEL[request.status]}
        </StatusTag>
      </div>
      <dl className="m-0 grid grid-cols-[repeat(auto-fit,minmax(180px,1fr))] gap-x-6 gap-y-2 text-small">
        <div>
          <dt className="text-label text-muted">Vai trò</dt>
          <dd className="m-0 font-semibold text-ink">{ACCOUNT_REQUEST_ROLE_LABEL[request.role]}</dd>
        </div>
        {request.replacesAccountId !== null && (
          <div>
            <dt className="text-label text-muted">Thay người nghỉ việc</dt>
            <dd className="m-0 font-semibold text-ink">
              {request.replacesName ?? request.replacesEmail}
              <span className="block font-normal text-muted">{request.replacesEmail}</span>
            </dd>
          </div>
        )}
        <div>
          <dt className="text-label text-muted">Cơ sở</dt>
          <dd className="m-0 font-semibold text-ink">
            {request.facilityCodes.length ? request.facilityCodes.join(", ") : "—"}
          </dd>
        </div>
        <div>
          <dt className="text-label text-muted">Người gửi</dt>
          <dd className="m-0 text-ink">
            {request.requestedByName ?? request.requestedByEmail} ·{" "}
            {formatDateTime(request.createdAt)}
          </dd>
        </div>
        {request.reviewedAt && (
          <div>
            <dt className="text-label text-muted">Admin xử lý</dt>
            <dd className="m-0 text-ink">
              {request.reviewedByEmail ?? "—"} · {formatDateTime(request.reviewedAt)}
            </dd>
          </div>
        )}
      </dl>
      {request.note && <p className="m-0 text-small text-muted">Ghi chú: {request.note}</p>}
      {request.reviewNote && (
        <p className="m-0 text-small text-danger">Lý do từ chối: {request.reviewNote}</p>
      )}
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </article>
  );
}
