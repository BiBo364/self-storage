import {
  AlertTriangle,
  ArrowRight,
  Check,
  ChevronDown,
  Filter,
  Mail,
  Phone,
  X,
} from "lucide-react";
import { useEffect, useState } from "react";
import { formatDate, formatPrice } from "../../app/format";
import {
  approveRentalRequest,
  fetchFacilityUnits,
  fetchManagedFacility,
  fetchRentalRequestQueue,
  rejectRentalRequest,
} from "../../app/fmQueue";
import { listUnitTypes } from "../../app/unitTypes";
import type {
  ManagedFacility,
  Navigate,
  RejectReason,
  RentalRequestStatus,
  RentalRequestSummary,
  StorageUnitSummary,
  UnitTypeSummary,
} from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { SurfaceState } from "../../components/ui/SurfaceState";

type TabValue = "Pending" | "Approved" | "Rejected" | "Expired";

type QueueItem = RentalRequestSummary & {
  approvedUnitCode?: string;
  rejectReasonLabel?: string;
};

const TABS: {
  value: TabValue;
  label: string;
  matches: (status: RentalRequestStatus) => boolean;
}[] = [
  { value: "Pending", label: "Chờ xử lý", matches: (status) => status === "Pending" },
  {
    value: "Approved",
    label: "Đã duyệt",
    matches: (status) => status === "Approved" || status === "Converted",
  },
  { value: "Rejected", label: "Từ chối", matches: (status) => status === "Rejected" },
  { value: "Expired", label: "Hết hạn", matches: (status) => status === "Expired" },
];

const STATUS_LABEL: Record<RentalRequestStatus, string> = {
  Pending: "Chờ xử lý",
  Approved: "Đã duyệt",
  Converted: "Đã chuyển đơn",
  Rejected: "Đã từ chối",
  Expired: "Hết hạn",
};

const STATUS_BADGE: Record<RentalRequestStatus, string> = {
  Pending: "bg-warning-soft text-warning",
  Approved: "bg-success-soft text-success",
  Converted: "bg-info-soft text-info",
  Rejected: "bg-danger-soft text-danger",
  Expired: "bg-surface-subtle text-muted",
};

const REJECT_REASON_LABEL: Record<RejectReason, string> = {
  UNIT_UNAVAILABLE: "Khoang không còn trống",
  UNIT_MISMATCH: "Khoang không phù hợp",
  CUSTOMER_REQUEST: "Khách yêu cầu",
  OTHER: "Lý do khác",
};

function nextActionLabel(request: QueueItem) {
  switch (request.nextAction) {
    case "APPROVE_REQUEST":
      return "Chờ xử lý";
    case "WAIT_CUSTOMER":
      return "Chờ khách phản hồi";
    case "REPROPOSE_UNIT":
      return "Cần đề xuất lại";
    default:
      return STATUS_LABEL[request.status];
  }
}

function nextActionBadge(request: QueueItem) {
  if (request.nextAction === "REPROPOSE_UNIT") return "bg-warning-soft text-warning";
  if (request.nextAction === "WAIT_CUSTOMER") return "bg-info-soft text-info";
  return STATUS_BADGE[request.status];
}

type LoadState = "loading" | "ready" | "error";

export function RentalRequestQueuePage({ navigate }: { navigate: Navigate }) {
  const [facility, setFacility] = useState<ManagedFacility | null>(null);
  const [items, setItems] = useState<QueueItem[]>([]);
  const [units, setUnits] = useState<StorageUnitSummary[]>([]);
  const [unitTypes, setUnitTypes] = useState<UnitTypeSummary[]>([]);
  const [loadState, setLoadState] = useState<LoadState>("loading");
  const [loadError, setLoadError] = useState("");
  const [reloadKey, setReloadKey] = useState(0);

  const [statusFilter, setStatusFilter] = useState<TabValue>("Pending");
  const [search, setSearch] = useState("");
  const [sizeFilter, setSizeFilter] = useState("");
  const [sort, setSort] = useState("newest");
  const [openId, setOpenId] = useState<number | null>(null);
  const [mode, setMode] = useState<"view" | "reject">("view");
  const [selectedUnitId, setSelectedUnitId] = useState("");
  const [rejectReason, setRejectReason] = useState<RejectReason>("UNIT_UNAVAILABLE");
  const [rejectNote, setRejectNote] = useState("");
  const [busyId, setBusyId] = useState<number | null>(null);
  const [actionError, setActionError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    let cancelled = false;

    async function load() {
      setLoadState("loading");
      setLoadError("");
      try {
        const managedFacility = await fetchManagedFacility(controller.signal);
        const [queue, facilityUnits, types] = await Promise.all([
          fetchRentalRequestQueue(managedFacility.id, controller.signal),
          fetchFacilityUnits(managedFacility.id, controller.signal),
          listUnitTypes(managedFacility.id, controller.signal),
        ]);
        if (cancelled) return;
        setFacility(managedFacility);
        setItems(queue.items);
        setUnits(facilityUnits);
        setUnitTypes(types);
        setLoadState("ready");
      } catch (error) {
        if (cancelled || controller.signal.aborted) return;
        setLoadError(error instanceof Error ? error.message : "Không thể tải hàng đợi yêu cầu.");
        setLoadState("error");
      }
    }

    void load();
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [reloadKey]);

  function unitTypeName(unitTypeId: number) {
    return unitTypes.find((type) => type.id === unitTypeId)?.name ?? String(unitTypeId);
  }

  function countsByTab() {
    return TABS.reduce<Record<TabValue, number>>(
      (counts, tab) => {
        counts[tab.value] = items.filter((item) => tab.matches(item.status)).length;
        return counts;
      },
      { Pending: 0, Approved: 0, Rejected: 0, Expired: 0 },
    );
  }

  const counts = countsByTab();
  const activeTab = TABS.find((tab) => tab.value === statusFilter) ?? TABS[0];
  const list = items
    .filter((request) => activeTab.matches(request.status))
    .filter((request) => {
      if (!search.trim()) return true;
      const query = search.trim().toLowerCase();
      return [request.customerName, request.customerEmail, request.customerPhone].some((value) =>
        value.toLowerCase().includes(query),
      );
    })
    .filter((request) => !sizeFilter || String(request.unitTypeId) === sizeFilter)
    .sort((left, right) => {
      const leftTime = new Date(left.createdAt).getTime();
      const rightTime = new Date(right.createdAt).getTime();
      return sort === "oldest" ? leftTime - rightTime : rightTime - leftTime;
    });
  const openRequest = items.find((request) => request.id === openId) ?? null;

  function openDetails(request: QueueItem) {
    setOpenId(request.id);
    setMode("view");
    setSelectedUnitId("");
    setRejectReason("UNIT_UNAVAILABLE");
    setRejectNote("");
    setActionError("");
  }

  async function approve(request: QueueItem) {
    if (!selectedUnitId) return;
    setBusyId(request.id);
    setActionError("");
    try {
      const result = await approveRentalRequest(request.id, Number(selectedUnitId));
      const unit = units.find((item) => item.id === Number(selectedUnitId));
      setItems((current) =>
        current.map((item) =>
          item.id === request.id
            ? { ...item, status: result.status, approvedUnitCode: unit?.code }
            : item,
        ),
      );
      setOpenId(null);
      setReloadKey((key) => key + 1);
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Không thể duyệt yêu cầu.");
    } finally {
      setBusyId(null);
    }
  }

  async function reject(request: QueueItem) {
    if (rejectReason === "OTHER" && !rejectNote.trim()) {
      setActionError("Vui lòng nhập ghi chú cho lý do khác.");
      return;
    }
    setBusyId(request.id);
    setActionError("");
    try {
      const result = await rejectRentalRequest(
        request.id,
        rejectReason,
        rejectNote.trim() || undefined,
      );
      setItems((current) =>
        current.map((item) =>
          item.id === request.id
            ? {
                ...item,
                status: result.status,
                rejectReasonLabel: REJECT_REASON_LABEL[result.rejectReason],
              }
            : item,
        ),
      );
      setOpenId(null);
      setReloadKey((key) => key + 1);
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "Không thể từ chối yêu cầu.");
    } finally {
      setBusyId(null);
    }
  }

  function clearFilters() {
    setSearch("");
    setSizeFilter("");
    setSort("newest");
  }

  const availableUnitsForRequest = (request: QueueItem) =>
    units.filter((unit) => unit.unitTypeId === request.unitTypeId && unit.status === "Available");

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Yêu cầu đặt kho</h1>
        <p className="mt-1.5 text-small text-muted">
          {counts.Pending} yêu cầu đang chờ ·{" "}
          {facility ? `${facility.code} · ${facility.name}` : "Đang tải cơ sở"}
        </p>
      </div>

      <div className="flex flex-wrap gap-1.5">
        {TABS.map((tab) => {
          const active = statusFilter === tab.value;
          return (
            <button
              key={tab.value}
              className={`inline-flex items-center gap-2 border-0 px-2.5 py-1.5 text-small font-semibold ${
                active
                  ? "bg-brand text-signal-ink"
                  : "bg-transparent text-muted hover:bg-surface-subtle hover:text-ink"
              }`}
              onClick={() => setStatusFilter(tab.value)}
            >
              {tab.label}
              <span
                className={`px-1.5 text-label ${
                  active ? "bg-signal-ink text-brand" : "bg-surface text-muted"
                }`}
              >
                {counts[tab.value]}
              </span>
            </button>
          );
        })}
      </div>

      <div className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)_minmax(0,0.8fr)_auto] items-end gap-3 max-[900px]:grid-cols-2 max-[760px]:grid-cols-1">
        <label className="grid gap-1.5">
          <span className="text-label text-muted">Tìm kiếm</span>
          <div className={INPUT_WRAP}>
            <input
              className={INPUT_ELEMENT}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Tên khách, email, số điện thoại…"
            />
          </div>
        </label>
        <label className="grid gap-1.5">
          <span className="text-label text-muted">Quy mô</span>
          <div className={INPUT_WRAP}>
            <select
              className={`${INPUT_ELEMENT} pr-8`}
              value={sizeFilter}
              onChange={(event) => setSizeFilter(event.target.value)}
            >
              <option value="">Tất cả quy mô</option>
              {unitTypes.map((unit) => (
                <option key={unit.id} value={String(unit.id)}>
                  {unit.area} m² · {unit.name}
                </option>
              ))}
            </select>
            <ChevronDown size={15} className="pointer-events-none absolute right-3 text-muted" />
          </div>
        </label>
        <label className="grid gap-1.5">
          <span className="text-label text-muted">Sắp xếp</span>
          <div className={INPUT_WRAP}>
            <select
              className={`${INPUT_ELEMENT} pr-8`}
              value={sort}
              onChange={(event) => setSort(event.target.value)}
            >
              <option value="newest">Mới nhất trước</option>
              <option value="oldest">Cũ nhất trước</option>
            </select>
            <ChevronDown size={15} className="pointer-events-none absolute right-3 text-muted" />
          </div>
        </label>
        <Button variant="secondary" onClick={clearFilters}>
          <Filter size={15} /> Xoá lọc
        </Button>
      </div>

      {loadState === "loading" && (
        <SurfaceState variant="loading" title="Đang tải hàng đợi yêu cầu…" />
      )}

      {loadState === "error" && (
        <SurfaceState
          variant="error"
          title="Không tải được hàng đợi yêu cầu"
          description={loadError}
          action={{
            label: "Thử lại",
            onClick: () => setReloadKey((key) => key + 1),
          }}
        />
      )}

      {loadState === "ready" && (
        <div className="overflow-x-auto">
          <div className="min-w-[980px]">
            <div className="grid grid-cols-[1.35fr_0.95fr_1.35fr_0.85fr_0.72fr_120px] gap-4 bg-[var(--dark)] px-3.5 py-2.5 text-label text-[rgba(233,234,230,.62)]">
              <span>Khách hàng</span>
              <span>Nhu cầu</span>
              <span>Nhận lúc</span>
              <span>Hạn xử lý</span>
              <span>Trạng thái</span>
              <span className="text-right">Hành động</span>
            </div>
            <div className="mt-1.5 grid gap-1.5">
              {list.map((request) => (
                <div
                  key={request.id}
                  className="grid grid-cols-[1.35fr_0.95fr_1.35fr_0.85fr_0.72fr_120px] items-center gap-4 bg-surface px-3.5 py-3"
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
                      {formatDate(request.startDate)} · {request.period} tháng
                    </span>
                  </div>
                  <span className="text-label text-ink">{formatDate(request.createdAt)}</span>
                  <span className="text-label text-ink">
                    {request.expiresAt ? formatDate(request.expiresAt) : "—"}
                  </span>
                  <div className="grid gap-1">
                    <span
                      className={`inline-flex w-fit items-center gap-1.5 px-2 py-1 text-label font-semibold ${nextActionBadge(request)}`}
                    >
                      <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />
                      {nextActionLabel(request)}
                    </span>
                    {request.orderId && (
                      <span className="text-label text-muted">
                        Đơn #{request.orderId} · {request.orderStatus ?? "—"} ·{" "}
                        {request.rejectionCount}/{request.maxRejectionCount} từ chối
                      </span>
                    )}
                  </div>
                  <div className="flex justify-end">
                    {request.nextAction === "REPROPOSE_UNIT" && request.orderId ? (
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => navigate(`/fm/rental-orders/${request.orderId}/re-propose`)}
                      >
                        Đề xuất lại
                      </Button>
                    ) : (
                      <Button variant="secondary" size="sm" onClick={() => openDetails(request)}>
                        {request.nextAction === "APPROVE_REQUEST" ? "Đề xuất" : "Xem"}
                      </Button>
                    )}
                  </div>
                </div>
              ))}
            </div>
            {list.length === 0 && (
              <div className="mt-2 bg-surface p-6 text-center">
                <p className="text-small font-semibold text-ink">Không có yêu cầu phù hợp.</p>
                <p className="mt-1 text-label text-muted">Thử đổi trạng thái hoặc xoá bộ lọc.</p>
              </div>
            )}
            <div className="mt-3 flex items-center justify-between gap-4 text-label text-muted">
              <span>{list.length} yêu cầu</span>
              <span>Dữ liệu từ hệ thống</span>
            </div>
          </div>
        </div>
      )}

      {openRequest && (
        <DetailPanel
          title={openRequest.customerName}
          eyebrow={`Yêu cầu · ${unitTypeName(openRequest.unitTypeId)}`}
          onClose={() => setOpenId(null)}
        >
          <div className="grid gap-2.5 text-small text-muted">
            <span className="flex items-center gap-2">
              <Phone size={14} /> {openRequest.customerPhone}
            </span>
            <span className="flex items-center gap-2">
              <Mail size={14} /> {openRequest.customerEmail}
            </span>
            <span>
              Bắt đầu {formatDate(openRequest.startDate)} · {openRequest.period} tháng
            </span>
            <span>Hạn xử lý {openRequest.expiresAt ? formatDate(openRequest.expiresAt) : "—"}</span>
          </div>

          {openRequest.orderId && (
            <div className="mt-4 grid gap-1.5 bg-surface-subtle p-3.5 text-label text-muted">
              <span className="font-semibold text-ink">
                Đơn #{openRequest.orderId} · {openRequest.orderStatus ?? "—"}
              </span>
              {openRequest.lastProposal && (
                <span>
                  Đề xuất gần nhất: khoang {openRequest.lastProposal.unitCode} ·{" "}
                  {openRequest.lastProposal.status}
                  {openRequest.lastProposal.note ? ` — ${openRequest.lastProposal.note}` : ""}
                </span>
              )}
              <span>
                Đã từ chối {openRequest.rejectionCount}/{openRequest.maxRejectionCount} lần
              </span>
            </div>
          )}

          {openRequest.nextAction === "REPROPOSE_UNIT" && openRequest.orderId && (
            <Button
              className="mt-4 w-full"
              onClick={() => {
                setOpenId(null);
                navigate(`/fm/rental-orders/${openRequest.orderId}/re-propose`);
              }}
            >
              Đề xuất lại <ArrowRight size={16} />
            </Button>
          )}

          {openRequest.nextAction === "WAIT_CUSTOMER" && (
            <div className="mt-4">
              <DemoNotice tone="info">Đang chờ khách phản hồi đề xuất gần nhất.</DemoNotice>
            </div>
          )}

          {openRequest.nextAction === "VIEW" && (
            <div className="mt-4">
              <DemoNotice tone="info">Không cần thao tác trên yêu cầu này.</DemoNotice>
            </div>
          )}

          {actionError && (
            <div className="mt-4">
              <DemoNotice tone="error">{actionError}</DemoNotice>
            </div>
          )}

          {openRequest.nextAction === "APPROVE_REQUEST" && mode === "view" && (
            <>
              <label className="mt-6 grid gap-1.5 text-label font-semibold text-ink">
                Chọn khoang phù hợp
                <select
                  className="rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink"
                  value={selectedUnitId}
                  onChange={(event) => setSelectedUnitId(event.target.value)}
                >
                  <option value="">— Chọn khoang —</option>
                  {availableUnitsForRequest(openRequest).map((unit) => (
                    <option key={unit.id} value={String(unit.id)}>
                      {unit.code} · {unit.unitTypeName} · {formatPrice(unit.monthlyPrice)} đ/tháng
                    </option>
                  ))}
                </select>
              </label>
              {availableUnitsForRequest(openRequest).length === 0 && (
                <p className="mb-0 mt-2 text-label text-danger">
                  Hiện không còn khoang trống đúng loại yêu cầu.
                </p>
              )}
              <div className="mt-5 flex items-center gap-3">
                <Button
                  pending={busyId === openRequest.id}
                  disabled={!selectedUnitId}
                  onClick={() => void approve(openRequest)}
                >
                  {busyId !== openRequest.id && <Check size={16} />}
                  Duyệt &amp; gán khoang
                </Button>
                <Button variant="secondary" onClick={() => setMode("reject")}>
                  <X size={16} /> Từ chối
                </Button>
              </div>
            </>
          )}

          {openRequest.nextAction === "APPROVE_REQUEST" && mode === "reject" && (
            <div className="mt-6 bg-surface-subtle p-4">
              <label className="grid gap-1.5 text-label font-semibold text-ink">
                Lý do từ chối
                <select
                  className="rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink"
                  value={rejectReason}
                  onChange={(event) => setRejectReason(event.target.value as RejectReason)}
                >
                  {Object.entries(REJECT_REASON_LABEL).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="mt-3 grid gap-1.5 text-label font-semibold text-ink">
                Ghi chú
                <textarea
                  className="min-h-[80px] rounded-[2px] border border-rule-strong bg-surface p-2.5 text-small font-normal text-ink"
                  value={rejectNote}
                  onChange={(event) => setRejectNote(event.target.value)}
                  placeholder={
                    rejectReason === "OTHER"
                      ? "Bắt buộc khi chọn lý do khác…"
                      : "Thông tin thêm cho khách (không bắt buộc)…"
                  }
                />
              </label>
              <div className="mt-3 flex items-center gap-2.5">
                <Button
                  disabled={busyId === openRequest.id}
                  pending={busyId === openRequest.id}
                  onClick={() => void reject(openRequest)}
                >
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

          {openRequest.nextAction === "VIEW" &&
            (openRequest.status === "Approved" || openRequest.status === "Converted") && (
              <div className="mt-6">
                <DemoNotice tone="success">
                  {openRequest.approvedUnitCode
                    ? `Đã gán khoang ${openRequest.approvedUnitCode} cho yêu cầu này.`
                    : "Yêu cầu đã được duyệt."}
                </DemoNotice>
              </div>
            )}

          {openRequest.status === "Rejected" && (
            <div className="mt-6">
              <DemoNotice tone="error">
                {openRequest.rejectReasonLabel ?? "Yêu cầu đã bị từ chối."}
              </DemoNotice>
            </div>
          )}

          {openRequest.status === "Expired" && (
            <div className="mt-6">
              <DemoNotice tone="pending">Yêu cầu đã hết hạn xử lý.</DemoNotice>
            </div>
          )}

          <Button
            variant="secondary"
            className="mt-4 w-full"
            onClick={() => {
              setOpenId(null);
              navigate("/fm/rental-requests");
            }}
          >
            Đóng <ArrowRight size={16} />
          </Button>
        </DetailPanel>
      )}
    </div>
  );
}
