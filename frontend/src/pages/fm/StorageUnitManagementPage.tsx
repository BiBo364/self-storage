import { AlertTriangle, ArrowRight, Check, Plus, RotateCcw } from "lucide-react";
import { FormEvent, useState } from "react";
import type { Navigate } from "../../app/types";
import { unitTypes } from "../../mocks/catalog";
import {
  createFmUnit,
  fmUnits,
  setFmUnitStatus,
  type FmUnit,
  type FmUnitStatus,
} from "../../mocks/fm";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { FormField, SelectField } from "../../components/ui/FormField";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

type StatusFilter = "all" | FmUnitStatus;

const STATUS_LABEL: Record<FmUnitStatus, string> = {
  Available: "Trống",
  OnHold: "Đang giữ",
  Reserved: "Đã đặt trước",
  Rented: "Đang cho thuê",
  Maintenance: "Bảo trì",
};

const STATUS_TONE: Record<FmUnitStatus, "success" | "warning" | "info" | "neutral"> = {
  Available: "success",
  OnHold: "warning",
  Reserved: "warning",
  Rented: "info",
  Maintenance: "neutral",
};

function unitTypeName(unitTypeId: string) {
  return unitTypes.find((type) => type.id === unitTypeId)?.name ?? unitTypeId;
}

export function StorageUnitManagementPage({ navigate }: { navigate: Navigate }) {
  const [, setRefresh] = useState(0);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [typeFilter, setTypeFilter] = useState<string>("all");
  const [openId, setOpenId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [maintenanceReason, setMaintenanceReason] = useState("");
  const [mode, setMode] = useState<"view" | "maintenance">("view");

  const list = fmUnits.filter(
    (unit) =>
      (statusFilter === "all" || unit.status === statusFilter) &&
      (typeFilter === "all" || unit.unitTypeId === typeFilter),
  );
  const openUnit = fmUnits.find((unit) => unit.id === openId) ?? null;

  function openDetails(unit: FmUnit) {
    setOpenId(unit.id);
    setMode("view");
    setMaintenanceReason("");
  }

  function submitCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const code = String(data.get("unitCode") ?? "").trim();
    const unitTypeId = String(data.get("unitTypeId") ?? "");
    const location = String(data.get("location") ?? "").trim();
    if (!code || !unitTypeId || !location) return;
    createFmUnit({ code, unitTypeId, location });
    setRefresh((count) => count + 1);
    setCreating(false);
    event.currentTarget.reset();
  }

  function confirmMaintenance(unit: FmUnit) {
    if (!maintenanceReason.trim()) return;
    setFmUnitStatus(unit.id, "Maintenance", maintenanceReason.trim());
    setRefresh((count) => count + 1);
    setOpenId(null);
  }

  function reopenAvailable(unit: FmUnit) {
    setFmUnitStatus(unit.id, "Available");
    setRefresh((count) => count + 1);
    setOpenId(null);
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Quản lý khoang chứa</h1>
          <p className="mt-1.5 text-small text-muted">
            Danh sách khoang vật lý tại cơ sở của bạn — giá thuê do BOM quản lý theo loại kho, không
            chỉnh ở đây.
          </p>
        </div>
        <Button onClick={() => setCreating((value) => !value)}>
          <Plus size={16} /> Tạo khoang mới
        </Button>
      </div>

      {creating && (
        <form onSubmit={submitCreate} className="bg-surface p-5">
          <h2 className="text-section text-ink">Khoang mới</h2>
          <div className="mt-4 grid grid-cols-3 gap-4 max-[760px]:grid-cols-1">
            <FormField label="Mã khoang" name="unitCode" placeholder="VD: TD-108" required />
            <SelectField
              label="Loại kho"
              name="unitTypeId"
              required
              options={unitTypes.map((type) => ({ value: type.id, label: type.name }))}
            />
            <FormField label="Vị trí" name="location" placeholder="VD: Tầng 2 · Dãy C" required />
          </div>
          <div className="mt-2 flex items-center gap-2.5">
            <Button type="submit">
              <Check size={16} /> Lưu khoang
            </Button>
            <button
              type="button"
              className="border-0 bg-transparent px-3 py-2 text-small text-muted hover:text-ink"
              onClick={() => setCreating(false)}
            >
              Huỷ
            </button>
          </div>
        </form>
      )}

      <div className="grid grid-cols-2 items-end gap-3 max-[760px]:grid-cols-1">
        <FilterSelect
          label="Trạng thái"
          value={statusFilter}
          onChange={(value) => setStatusFilter(value as StatusFilter)}
        >
          <option value="all">Tất cả trạng thái</option>
          <option value="Available">Trống</option>
          <option value="OnHold">Đang giữ</option>
          <option value="Reserved">Đã đặt trước</option>
          <option value="Rented">Đang cho thuê</option>
          <option value="Maintenance">Bảo trì</option>
        </FilterSelect>
        <FilterSelect label="Loại kho" value={typeFilter} onChange={setTypeFilter}>
          <option value="all">Tất cả loại kho</option>
          {unitTypes.map((type) => (
            <option key={type.id} value={type.id}>
              {type.name}
            </option>
          ))}
        </FilterSelect>
      </div>

      {list.length === 0 ? (
        <SurfaceState variant="empty" title="Không có khoang nào phù hợp bộ lọc" />
      ) : (
        <div className="grid gap-2">
          {list.map((unit) => (
            <button
              key={unit.id}
              className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
              onClick={() => openDetails(unit)}
            >
              <div>
                <strong className="block text-[1rem] font-medium text-ink">{unit.code}</strong>
                <span className="mt-1 block text-label text-muted">
                  {unitTypeName(unit.unitTypeId)} · {unit.size} · {unit.location}
                </span>
              </div>
              <StatusTag tone={STATUS_TONE[unit.status]}>{STATUS_LABEL[unit.status]}</StatusTag>
            </button>
          ))}
        </div>
      )}

      {openUnit && (
        <DetailPanel
          title={openUnit.code}
          eyebrow={`${unitTypeName(openUnit.unitTypeId)} · ${openUnit.size}`}
          onClose={() => setOpenId(null)}
        >
          <div className="grid gap-2 text-small text-muted">
            <span>Vị trí: {openUnit.location}</span>
            <span className="flex items-center gap-2">
              Trạng thái hiện tại:
              <StatusTag tone={STATUS_TONE[openUnit.status]}>
                {STATUS_LABEL[openUnit.status]}
              </StatusTag>
            </span>
          </div>

          {openUnit.status === "Available" && mode === "view" && (
            <div className="mt-6">
              <Button variant="secondary" onClick={() => setMode("maintenance")}>
                <AlertTriangle size={16} /> Chuyển sang Bảo trì
              </Button>
            </div>
          )}

          {openUnit.status === "Available" && mode === "maintenance" && (
            <div className="mt-6 bg-surface-subtle p-4">
              <label className="grid gap-1.5 text-label text-muted">
                Lý do chuyển bảo trì (bắt buộc, sẽ lưu vào nhật ký thao tác)
                <textarea
                  className="min-h-[80px] rounded-[2px] border border-rule-strong bg-surface p-2.5 text-small font-normal text-ink"
                  value={maintenanceReason}
                  onChange={(event) => setMaintenanceReason(event.target.value)}
                  placeholder="VD: Phát hiện ẩm mốc, cần xử lý trước khi cho thuê tiếp…"
                />
              </label>
              <div className="mt-3 flex items-center gap-2.5">
                <Button
                  disabled={!maintenanceReason.trim()}
                  onClick={() => confirmMaintenance(openUnit)}
                >
                  <AlertTriangle size={16} /> Xác nhận chuyển bảo trì
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

          {openUnit.status === "Maintenance" && (
            <div className="mt-6 grid gap-3">
              {openUnit.maintenanceReason && (
                <DemoNotice tone="pending">Lý do: {openUnit.maintenanceReason}</DemoNotice>
              )}
              <Button onClick={() => reopenAvailable(openUnit)}>
                <RotateCcw size={16} /> Chuyển về Trống
              </Button>
            </div>
          )}

          {(openUnit.status === "OnHold" || openUnit.status === "Reserved") && (
            <div className="mt-6">
              <DemoNotice tone="pending">
                Khoang đang gắn với 1 yêu cầu/đơn hàng đang xử lý — cần giải quyết yêu cầu/đơn đó
                trước, không đổi trạng thái trực tiếp ở đây.
              </DemoNotice>
            </div>
          )}

          {openUnit.status === "Rented" && (
            <div className="mt-6">
              <DemoNotice tone="pending">
                Khoang đang có khách thuê — không tự ý chuyển bảo trì. Nếu có sự cố, hãy tạo yêu cầu
                hỗ trợ để FS xử lý.
              </DemoNotice>
              <Button
                variant="secondary"
                className="mt-4"
                onClick={() => navigate("/fm/support-requests")}
              >
                Tới hàng chờ yêu cầu hỗ trợ <ArrowRight size={14} />
              </Button>
            </div>
          )}
        </DetailPanel>
      )}
    </div>
  );
}
