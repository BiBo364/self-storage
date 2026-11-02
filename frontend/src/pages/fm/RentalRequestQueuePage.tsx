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
import { useState } from "react";
import type { Navigate } from "../../app/types";
import { unitTypes } from "../../mocks/catalog";
import {
  fmUnits,
  rentalOrders,
  rentalRequests as initialRequests,
  type RentalRequest,
} from "../../mocks/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";

type StatusFilter = "all" | RentalRequest["status"];

const STATUS_LABEL: Record<RentalRequest["status"], string> = {
  Pending: "Chờ xử lý",
  Approved: "Đã duyệt",
  Rejected: "Đã từ chối",
  Expired: "Hết hạn",
  Wishlisted: "Danh sách chờ",
};

const STATUS_BADGE: Record<RentalRequest["status"], string> = {
  Pending: "bg-warning-soft text-warning",
  Approved: "bg-success-soft text-success",
  Rejected: "bg-danger-soft text-danger",
  Expired: "bg-surface-subtle text-muted",
  Wishlisted: "bg-brand-soft text-ink",
};

const TABS: { value: RentalRequest["status"]; label: string }[] = [
  { value: "Pending", label: "Chờ xử lý" },
  { value: "Approved", label: "Đã duyệt" },
  { value: "Rejected", label: "Từ chối" },
  { value: "Expired", label: "Hết hạn" },
  { value: "Wishlisted", label: "Danh sách chờ" },
];

function unitTypeName(unitTypeId: string) {
  return unitTypes.find((type) => type.id === unitTypeId)?.name ?? unitTypeId;
}

function dateValue(value: string) {
  const [day, month, year] = value.split("/").map(Number);
  return new Date(year, month - 1, day).getTime();
}

function countsByStatus(items: RentalRequest[]) {
  return TABS.reduce<Record<string, number>>((counts, tab) => {
    counts[tab.value] = items.filter((item) => item.status === tab.value).length;
    return counts;
  }, {});
}

export function RentalRequestQueuePage({ navigate }: { navigate: Navigate }) {
  const openOrders = rentalOrders.filter((order) => order.status === "OpenForRepropose");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("Pending");
  const [search, setSearch] = useState("");
  const [sizeFilter, setSizeFilter] = useState("");
  const [sort, setSort] = useState("newest");
  const [items, setItems] = useState<RentalRequest[]>(initialRequests);
  const [openId, setOpenId] = useState<string | null>(null);
  const [mode, setMode] = useState<"view" | "reject">("view");
  const [selectedUnitId, setSelectedUnitId] = useState("");
  const [rejectReason, setRejectReason] = useState("");
  const [busyId, setBusyId] = useState<string | null>(null);
  const [conflictId, setConflictId] = useState<string | null>(null);

  const counts = countsByStatus(items);
  const list = items
    .filter((request) => statusFilter === "all" || request.status === statusFilter)
    .filter((request) => {
      if (!search.trim()) return true;
      const query = search.trim().toLowerCase();
      return [request.customerName, request.customerEmail, request.customerPhone].some((value) =>
        value.toLowerCase().includes(query),
      );
    })
    .filter((request) => !sizeFilter || request.unitTypeId === sizeFilter)
    .sort((left, right) =>
      sort === "oldest"
        ? dateValue(left.createdAt) - dateValue(right.createdAt)
        : dateValue(right.createdAt) - dateValue(left.createdAt),
    );
  const openRequest = items.find((request) => request.id === openId) ?? null;

  function openDetails(request: RentalRequest) {
    setOpenId(request.id);
    setMode("view");
    setSelectedUnitId("");
    setRejectReason("");
    setConflictId(null);
  }

  function approve(request: RentalRequest) {
    if (!selectedUnitId) return;
    setBusyId(request.id);
    window.setTimeout(() => {
      setBusyId(null);
      if (request.simulateConflict) {
        setConflictId(request.id);
        return;
      }
      const unit = fmUnits.find((item) => item.id === selectedUnitId);
      setItems((current) =>
        current.map((item) =>
          item.id === request.id
            ? { ...item, status: "Approved", approvedUnitCode: unit?.code }
            : item,
        ),
      );
      setOpenId(null);
    }, 800);
  }

  function reject(request: RentalRequest) {
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

  function clearFilters() {
    setSearch("");
    setSizeFilter("");
    setSort("newest");
  }

  const availableUnitsForRequest = (request: RentalRequest) =>
    fmUnits.filter((unit) => unit.unitTypeId === request.unitTypeId && unit.status === "Available");

  return (
    <div className="grid gap-6">
      {openOrders.length > 0 && (
        <div className="flex flex-wrap items-center justify-between gap-3 bg-surface p-4">
          <span className="text-small text-ink">
            Có {openOrders.length} đơn cần đề xuất lại khoang (khách đã từ chối đề xuất trước).
          </span>
          <button
            className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-small font-semibold text-accent hover:text-ink"
            onClick={() => navigate(`/fm/rental-orders/${openOrders[0].id}/re-propose`)}
          >
            Xem ngay <ArrowRight size={14} />
          </button>
        </div>
      )}

      <div>
        <h1 className="m-0 text-title text-ink">Yêu cầu đặt kho</h1>
        <p className="mt-1.5 text-small text-muted">
          {counts.Pending ?? 0} yêu cầu đang chờ · Cập nhật từ dữ liệu minh hoạ
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
                {counts[tab.value] ?? 0}
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
                <option key={unit.id} value={unit.id}>
                  {unit.area} · {unit.name}
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

      <div className="overflow-x-auto">
        <div className="min-w-[980px]">
          <div className="grid grid-cols-[1.35fr_0.95fr_1.35fr_0.7fr_0.72fr_120px] gap-4 bg-[var(--dark)] px-3.5 py-2.5 text-label text-[rgba(233,234,230,.62)]">
            <span>Khách hàng</span>
            <span>Nhu cầu</span>
            <span>Ghi chú</span>
            <span>Nhận lúc</span>
            <span>Trạng thái</span>
            <span className="text-right">Hành động</span>
          </div>
          <div className="mt-1.5 grid gap-1.5">
            {list.map((request) => (
              <div
                key={request.id}
                className="grid grid-cols-[1.35fr_0.95fr_1.35fr_0.7fr_0.72fr_120px] items-center gap-4 bg-surface px-3.5 py-3"
              >
                <div className="min-w-0">
                  <strong className="block truncate text-small font-semibold text-ink">
                    {request.customerName}
                  </strong>
                  <span className="mt-0.5 block truncate text-label text-muted">
                    {request.customerPhone} · {request.customerEmail}
                  </span>
                  {!request.hasAccount && (
                    <span className="mt-1 inline-block bg-warning-soft px-1.5 py-0.5 text-label text-warning">
                      Chưa có tài khoản
                    </span>
                  )}
                </div>
                <div className="text-small text-ink">
                  {unitTypeName(request.unitTypeId)}
                  <span className="mt-0.5 block text-label text-muted">{request.desiredSize}</span>
                </div>
                <p className="line-clamp-2 text-label leading-relaxed text-muted">{request.note}</p>
                <span className="text-label text-ink">{request.createdAt}</span>
                <span
                  className={`inline-flex w-fit items-center gap-1.5 px-2 py-1 text-label font-semibold ${STATUS_BADGE[request.status]}`}
                >
                  <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />
                  {STATUS_LABEL[request.status]}
                </span>
                <div className="flex justify-end">
                  <Button variant="secondary" size="sm" onClick={() => openDetails(request)}>
                    {request.status === "Pending" ? "Đề xuất" : "Xem"}
                  </Button>
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
            <span>
              1–{list.length} / {list.length} yêu cầu
            </span>
            <span>Dữ liệu minh hoạ</span>
          </div>
        </div>
      </div>

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
            {!openRequest.hasAccount && (
              <span className="w-fit bg-warning-soft px-2 py-0.5 text-label font-semibold text-warning">
                Chưa có tài khoản — khách sẽ nhận email mời đăng ký
              </span>
            )}
          </div>
          <p className="mb-0 mt-4 text-small leading-relaxed text-ink">{openRequest.note}</p>

          {conflictId === openRequest.id && (
            <div className="mt-5">
              <DemoNotice tone="error">
                Khoang đã không còn khả dụng, vui lòng chọn khoang khác.
              </DemoNotice>
            </div>
          )}

          {openRequest.status === "Pending" && mode === "view" && (
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
                    <option key={unit.id} value={unit.id}>
                      {unit.code} · {unit.size}
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
                  onClick={() => approve(openRequest)}
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

          {openRequest.status === "Pending" && mode === "reject" && (
            <div className="mt-6 bg-surface-subtle p-4">
              <label className="grid gap-1.5 text-label font-semibold text-ink">
                Lý do từ chối
                <textarea
                  className="min-h-[80px] rounded-[2px] border border-rule-strong bg-surface p-2.5 text-small font-normal text-ink"
                  value={rejectReason}
                  onChange={(event) => setRejectReason(event.target.value)}
                  placeholder="Cho khách biết vì sao yêu cầu chưa phù hợp…"
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

          {openRequest.status === "Approved" && (
            <div className="mt-6">
              <DemoNotice tone="success">
                Đã gán khoang {openRequest.approvedUnitCode} cho yêu cầu này.
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
