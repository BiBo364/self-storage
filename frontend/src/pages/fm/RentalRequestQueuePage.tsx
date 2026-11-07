import {
  AlertTriangle,
  ArrowRight,
  Check,
  ChevronLeft,
  ChevronRight,
  Mail,
  Phone,
  X,
} from "lucide-react";
import { useState } from "react";
import { formatDate, formatDateTime, formatMoney } from "../../app/format";
import {
  approveRentalRequest,
  fetchRentalOrders,
  fetchRentalRequests,
  fetchStorageUnits,
  fetchUnitTypes,
  labelOf,
  loadErrorState,
  rejectRentalRequest,
  toneOf,
  useFmAction,
  useFmLoad,
  type RentalRequestRejectReason,
  type RentalRequestSummary,
} from "../../app/services/fm";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { INPUT_ELEMENT, INPUT_WRAP, SelectField } from "../../components/ui/FormField";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const PAGE_SIZE = 20;

const STATUS_LABEL: Record<string, string> = {
  Pending: "Chờ xử lý",
  Approved: "Đã duyệt",
  Converted: "Đã tạo đơn",
  Rejected: "Đã từ chối",
  Expired: "Hết hạn",
};

const STATUS_TONE: Record<string, "success" | "warning" | "danger" | "info" | "neutral"> = {
  Pending: "warning",
  Approved: "success",
  Converted: "info",
  Rejected: "danger",
  Expired: "neutral",
};

const TABS: { value: string; label: string }[] = [
  { value: "Pending", label: "Chờ xử lý" },
  { value: "Approved", label: "Đã duyệt" },
  { value: "Converted", label: "Đã tạo đơn" },
  { value: "Rejected", label: "Từ chối" },
  { value: "Expired", label: "Hết hạn" },
  { value: "", label: "Tất cả" },
];

const REJECT_REASONS: { value: RentalRequestRejectReason; label: string }[] = [
  { value: "UNIT_UNAVAILABLE", label: "Không còn khoang phù hợp" },
  { value: "UNIT_MISMATCH", label: "Nhu cầu không khớp loại khoang" },
  { value: "CUSTOMER_REQUEST", label: "Khách yêu cầu huỷ" },
  { value: "OTHER", label: "Khác" },
];

const TABLE_COLUMNS = "grid-cols-[1.4fr_1.1fr_0.8fr_0.8fr_0.75fr_110px]";

export function RentalRequestQueuePage({ navigate }: { navigate: Navigate }) {
  const [status, setStatus] = useState("Pending");
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const requests = useFmLoad(`requests:${status}:${page}`, (facility) =>
    fetchRentalRequests(facility.id, { status, page, size: PAGE_SIZE }),
  );
  const unitTypes = useFmLoad("unit-types", (facility) => fetchUnitTypes(facility.id));
  const orders = useFmLoad("orders:repropose", () => fetchRentalOrders());

  const reproposeOrders = (orders.data ?? []).filter((item) =>
    item.availableActions.includes("REPROPOSE"),
  );

  function unitTypeName(unitTypeId: number) {
    return unitTypes.data?.find((type) => type.id === unitTypeId)?.name ?? `Loại #${unitTypeId}`;
  }

  function changeStatus(value: string) {
    setStatus(value);
    setPage(0);
    setNotice(null);
  }

  const result = requests.data;
  const items = result?.items ?? [];
  const total = result?.total ?? 0;
  const openRequest = items.find((item) => item.id === openId) ?? null;

  return (
    <div className="grid gap-6">
      {reproposeOrders.length > 0 && (
        <div className="flex flex-wrap items-center justify-between gap-3 bg-surface p-4">
          <span className="text-small text-ink">
            Có {reproposeOrders.length} đơn thuê cần đề xuất lại khoang.
          </span>
          <button
            className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-small font-semibold text-accent hover:text-ink"
            onClick={() => navigate(`/fm/rental-orders/${reproposeOrders[0].order.id}/re-propose`)}
          >
            Đề xuất ngay <ArrowRight size={14} />
          </button>
        </div>
      )}

      <div>
        <h1 className="m-0 text-title text-ink">Yêu cầu đặt kho</h1>
        <p className="mt-1.5 text-small text-muted">
          {requests.facility ? `Cơ sở ${requests.facility.name}` : "Cơ sở của bạn"}
          {result ? ` · ${total} yêu cầu` : ""}
        </p>
      </div>

      <div className="flex flex-wrap gap-1.5" role="tablist" aria-label="Lọc theo trạng thái">
        {TABS.map((tab) => {
          const active = status === tab.value;
          return (
            <button
              key={tab.value || "all"}
              role="tab"
              aria-selected={active}
              className={`inline-flex items-center gap-2 border-0 px-2.5 py-1.5 text-small font-semibold ${
                active
                  ? "bg-brand text-signal-ink"
                  : "bg-transparent text-muted hover:bg-surface-subtle hover:text-ink"
              }`}
              onClick={() => changeStatus(tab.value)}
            >
              {tab.label}
            </button>
          );
        })}
      </div>

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}

      {requests.error ? (
        <SurfaceState
          {...loadErrorState(requests.error, "hàng đợi yêu cầu đặt kho")}
          action={{ label: "Thử lại", onClick: requests.reload }}
        />
      ) : !result ? (
        <SurfaceState variant="loading" title="Đang tải yêu cầu đặt kho…" />
      ) : items.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Không có yêu cầu nào"
          description="Thử chọn trạng thái khác để xem các yêu cầu đã xử lý."
        />
      ) : (
        <div className="overflow-x-auto" aria-busy={requests.loading}>
          <div className={`min-w-[900px] ${requests.loading ? "opacity-60" : ""}`}>
            <div
              className={`grid ${TABLE_COLUMNS} gap-4 bg-[var(--dark)] px-3.5 py-2.5 text-label text-[rgba(233,234,230,.62)]`}
            >
              <span>Khách hàng</span>
              <span>Nhu cầu</span>
              <span>Nhận lúc</span>
              <span>Hạn xử lý</span>
              <span>Trạng thái</span>
              <span className="text-right">Hành động</span>
            </div>
            <div className="mt-1.5 grid gap-1.5">
              {items.map((request) => (
                <div
                  key={request.id}
                  className={`grid ${TABLE_COLUMNS} items-center gap-4 bg-surface px-3.5 py-3`}
                >
                  <div className="min-w-0">
                    <strong className="block truncate text-small font-semibold text-ink">
                      {request.customerName}
                    </strong>
                    <span className="mt-0.5 block truncate text-label text-muted">
                      {request.customerPhone} · {request.customerEmail}
                    </span>
                  </div>
                  <div className="text-small text-ink">
                    {unitTypeName(request.unitTypeId)}
                    <span className="mt-0.5 block text-label text-muted">
                      Từ {formatDate(request.startDate)} · {request.period} tháng
                    </span>
                  </div>
                  <span className="text-label text-ink tabular-nums">
                    {formatDateTime(request.createdAt)}
                  </span>
                  <span className="text-label text-ink tabular-nums">
                    {formatDateTime(request.expiresAt)}
                  </span>
                  <StatusTag tone={toneOf(STATUS_TONE, request.status)}>
                    {labelOf(STATUS_LABEL, request.status)}
                  </StatusTag>
                  <div className="flex justify-end">
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => {
                        setNotice(null);
                        setOpenId(request.id);
                      }}
                    >
                      {request.status === "Pending" ? "Xử lý" : "Xem"}
                    </Button>
                  </div>
                </div>
              ))}
            </div>
            <div className="mt-3 flex items-center justify-between gap-4 text-label text-muted">
              <span>
                {page * PAGE_SIZE + 1}–{page * PAGE_SIZE + items.length} / {total} yêu cầu
              </span>
              <div className="flex gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={page === 0}
                  onClick={() => setPage((value) => Math.max(0, value - 1))}
                >
                  <ChevronLeft size={14} /> Trước
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={(page + 1) * PAGE_SIZE >= total}
                  onClick={() => setPage((value) => value + 1)}
                >
                  Sau <ChevronRight size={14} />
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}

      {openRequest && (
        <RequestPanel
          request={openRequest}
          unitTypeName={unitTypeName(openRequest.unitTypeId)}
          onClose={() => setOpenId(null)}
          onDone={(message) => {
            setOpenId(null);
            setNotice(message);
            requests.reload();
            orders.reload();
          }}
        />
      )}
    </div>
  );
}

function RequestPanel({
  request,
  unitTypeName,
  onClose,
  onDone,
}: {
  request: RentalRequestSummary;
  unitTypeName: string;
  onClose: () => void;
  onDone: (message: string) => void;
}) {
  const [mode, setMode] = useState<"approve" | "reject">("approve");
  const [unitId, setUnitId] = useState("");
  const [reason, setReason] = useState<RentalRequestRejectReason | "">("");
  const [note, setNote] = useState("");
  const action = useFmAction();
  const isPending = request.status === "Pending";

  const units = useFmLoad(`available-units:${request.id}:${isPending}`, (facility) =>
    isPending ? fetchStorageUnits(facility.id, { status: "Available" }) : Promise.resolve([]),
  );

  // Khoang đúng loại khách chọn xếp lên đầu; FM vẫn được chọn khoang khác, backend quyết định.
  const unitOptions = [...(units.data ?? [])].sort(
    (left, right) =>
      Number(right.unitTypeId === request.unitTypeId) -
      Number(left.unitTypeId === request.unitTypeId),
  );

  async function approve() {
    const outcome = await action.run("approve", () =>
      approveRentalRequest(request.id, Number(unitId)),
    );
    if (outcome.ok) {
      onDone(
        outcome.value.status === "Rejected"
          ? `Yêu cầu của ${request.customerName} đã bị từ chối do tài khoản khách đang bị chặn.`
          : `Đã duyệt yêu cầu của ${request.customerName}.`,
      );
    } else {
      units.reload();
    }
  }

  async function reject() {
    if (!reason) return;
    const outcome = await action.run("reject", () =>
      rejectRentalRequest(request.id, reason, note.trim()),
    );
    if (outcome.ok) onDone(`Đã từ chối yêu cầu của ${request.customerName}.`);
  }

  return (
    <DetailPanel
      title={request.customerName}
      eyebrow={`Yêu cầu #${request.id} · ${unitTypeName}`}
      onClose={onClose}
    >
      <div className="grid gap-2.5 text-small text-muted">
        <span className="flex items-center gap-2">
          <Phone size={14} /> {request.customerPhone}
        </span>
        <span className="flex items-center gap-2">
          <Mail size={14} /> {request.customerEmail}
        </span>
      </div>

      <dl className="mt-5 grid grid-cols-2 gap-x-4 gap-y-3 text-small">
        <div>
          <dt className="text-label text-muted">Loại khoang</dt>
          <dd className="m-0 text-ink">{unitTypeName}</dd>
        </div>
        <div>
          <dt className="text-label text-muted">Bắt đầu thuê</dt>
          <dd className="m-0 text-ink tabular-nums">{formatDate(request.startDate)}</dd>
        </div>
        <div>
          <dt className="text-label text-muted">Thời hạn</dt>
          <dd className="m-0 text-ink">{request.period} tháng</dd>
        </div>
        <div>
          <dt className="text-label text-muted">Trạng thái</dt>
          <dd className="m-0">
            <StatusTag tone={toneOf(STATUS_TONE, request.status)}>
              {labelOf(STATUS_LABEL, request.status)}
            </StatusTag>
          </dd>
        </div>
        <div>
          <dt className="text-label text-muted">Nhận lúc</dt>
          <dd className="m-0 text-ink tabular-nums">{formatDateTime(request.createdAt)}</dd>
        </div>
        <div>
          <dt className="text-label text-muted">Hạn xử lý</dt>
          <dd className="m-0 text-ink tabular-nums">{formatDateTime(request.expiresAt)}</dd>
        </div>
      </dl>

      {action.error && <DemoNotice tone="error">{action.error}</DemoNotice>}

      {isPending && mode === "approve" && (
        <div className="mt-6">
          {units.error ? (
            <DemoNotice tone="error">
              Không tải được danh sách khoang: {units.error.message}
            </DemoNotice>
          ) : units.loading && !units.data ? (
            <DemoNotice tone="pending">Đang tải khoang trống…</DemoNotice>
          ) : unitOptions.length === 0 ? (
            <DemoNotice tone="info">Cơ sở hiện không còn khoang trống.</DemoNotice>
          ) : (
            <SelectField
              label="Gán khoang trống"
              name="approve-unit"
              required
              value={unitId}
              onChange={setUnitId}
              placeholder="— Chọn khoang —"
              options={unitOptions.map((unit) => ({
                value: String(unit.id),
                label: `${unit.code} · ${unit.unitTypeName}${
                  unit.unitTypeId === request.unitTypeId ? " (đúng loại khách chọn)" : ""
                } · ${formatMoney(unit.monthlyPrice)}/tháng`,
              }))}
            />
          )}
          <div className="mt-2 flex flex-wrap items-center gap-3">
            <Button pending={action.pending === "approve"} disabled={!unitId} onClick={approve}>
              {action.pending !== "approve" && <Check size={16} />}
              Duyệt &amp; gán khoang
            </Button>
            <Button
              variant="secondary"
              onClick={() => {
                action.clear();
                setMode("reject");
              }}
            >
              <X size={16} /> Từ chối
            </Button>
          </div>
        </div>
      )}

      {isPending && mode === "reject" && (
        <div className="mt-6 bg-surface-subtle p-4">
          <SelectField
            label="Lý do từ chối"
            name="reject-reason"
            required
            value={reason}
            onChange={(value) => setReason(value as RentalRequestRejectReason | "")}
            placeholder="— Chọn lý do —"
            options={REJECT_REASONS}
          />
          <label className="grid gap-1.5 text-label text-muted" htmlFor="reject-note">
            Ghi chú{reason === "OTHER" ? " (bắt buộc khi chọn Khác)" : " (tuỳ chọn)"}
            <div className={INPUT_WRAP}>
              <textarea
                id="reject-note"
                className={`${INPUT_ELEMENT} min-h-[80px] py-2`}
                value={note}
                maxLength={2000}
                onChange={(event) => setNote(event.target.value)}
                placeholder="Gửi kèm trong email báo khách…"
              />
            </div>
          </label>
          <div className="mt-3 flex items-center gap-2.5">
            <Button
              variant="danger"
              pending={action.pending === "reject"}
              disabled={!reason}
              onClick={reject}
            >
              {action.pending !== "reject" && <AlertTriangle size={16} />}
              Xác nhận từ chối
            </Button>
            <Button
              variant="secondary"
              onClick={() => {
                action.clear();
                setMode("approve");
              }}
            >
              Quay lại
            </Button>
          </div>
        </div>
      )}

      {!isPending && (
        <DemoNotice tone="info">
          Yêu cầu đã ở trạng thái “{labelOf(STATUS_LABEL, request.status)}” — không còn thao tác.
        </DemoNotice>
      )}
    </DetailPanel>
  );
}
