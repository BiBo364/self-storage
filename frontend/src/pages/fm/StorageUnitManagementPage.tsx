import {
  ArrowRight,
  Check,
  KeyRound,
  LayoutGrid,
  List,
  Lock,
  MapPin,
  Plus,
  RotateCcw,
  Wrench,
} from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { formatDateTime, formatMoney } from "../../app/format";
import {
  createStorageUnit,
  fetchStorageUnits,
  fetchUnitTypes,
  holdStorageUnitMaintenance,
  labelOf,
  loadErrorState,
  setStorageUnitLocation,
  setStorageUnitMaintenance,
  toneOf,
  UNIT_STATUS_LABEL,
  UNIT_STATUS_TONE,
  fetchFacilityAccessOptions,
  updateFacilityAccessOptions,
  updateStorageUnit,
  useFmAction,
  useFmLoad,
  type FacilityRow,
  type StorageUnit,
  type UnitLocationInput,
  type UnitType,
} from "../../app/services/fm";
import { isPlaced, locationText, type AisleSide } from "../../app/facilityLayout";
import {
  FacilityLayoutDiagram,
  type DiagramUnit,
} from "../../components/domain/FacilityLayoutDiagram";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import {
  FIELD_LABEL,
  FormField,
  INPUT_ELEMENT,
  INPUT_WRAP,
  SelectField,
} from "../../components/ui/FormField";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const STATUS_OPTIONS = ["Available", "Reserved", "Rented", "Maintenance"];

const EMPTY_LOCATION: UnitLocationInput = { floorNo: null, aisleSide: null, aislePosition: null };

/** Ba ô tầng / bên lối đi / thứ tự từ cửa vào. Tầng "—" = chưa xếp lên sơ đồ. */
function LocationFields({
  idPrefix,
  floorCount,
  value,
  onChange,
}: {
  idPrefix: string;
  floorCount: number;
  value: UnitLocationInput;
  onChange: (value: UnitLocationInput) => void;
}) {
  const select =
    "min-h-[44px] w-full rounded-[2px] border border-rule-strong bg-surface px-3 text-body text-ink outline-0 focus:border-brand";
  return (
    <div className="grid grid-cols-3 gap-3 max-[560px]:grid-cols-1">
      <div>
        <label className={FIELD_LABEL} htmlFor={`${idPrefix}-floor`}>
          Tầng
        </label>
        <select
          id={`${idPrefix}-floor`}
          className={select}
          value={value.floorNo ?? ""}
          onChange={(event) =>
            onChange(
              event.target.value === ""
                ? EMPTY_LOCATION
                : {
                    floorNo: Number(event.target.value),
                    aisleSide: value.aisleSide ?? "Left",
                    aislePosition: value.aislePosition ?? 1,
                  },
            )
          }
        >
          <option value="">— Chưa xếp —</option>
          {Array.from({ length: Math.max(floorCount, 1) }, (_, i) => i + 1).map((floor) => (
            <option key={floor} value={floor}>
              Tầng {floor}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label className={FIELD_LABEL} htmlFor={`${idPrefix}-side`}>
          Bên lối đi (nhìn từ cửa vào)
        </label>
        <select
          id={`${idPrefix}-side`}
          className={select}
          disabled={value.floorNo === null}
          value={value.aisleSide ?? ""}
          onChange={(event) => onChange({ ...value, aisleSide: event.target.value as AisleSide })}
        >
          <option value="Left">Bên trái</option>
          <option value="Right">Bên phải</option>
        </select>
      </div>
      <div>
        <label className={FIELD_LABEL} htmlFor={`${idPrefix}-position`}>
          Ô thứ (1 = sát cửa)
        </label>
        <input
          id={`${idPrefix}-position`}
          type="number"
          min={1}
          max={200}
          className={select}
          disabled={value.floorNo === null}
          value={value.aislePosition ?? ""}
          onChange={(event) =>
            onChange({ ...value, aislePosition: Math.max(1, Number(event.target.value) || 1) })
          }
        />
      </div>
    </div>
  );
}

function parsePrice(value: string): number | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : Number(trimmed);
}

export function StorageUnitManagementPage({ navigate }: { navigate: Navigate }) {
  const [status, setStatus] = useState("");
  const [unitTypeId, setUnitTypeId] = useState("");
  const [creating, setCreating] = useState(false);
  const [openId, setOpenId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [view, setView] = useState<"list" | "map">("list");
  const [newLocation, setNewLocation] = useState<UnitLocationInput>(EMPTY_LOCATION);
  const createAction = useFmAction();
  const facilityInfo = useFmLoad("facility-info", (facility) =>
    fetchFacilityAccessOptions(facility.id),
  );
  const floorCount = facilityInfo.data?.floorCount ?? 1;

  const units = useFmLoad(`units:${status}:${unitTypeId}`, (facility) =>
    fetchStorageUnits(facility.id, { status, unitTypeId }),
  );
  const unitTypes = useFmLoad("unit-types", (facility) => fetchUnitTypes(facility.id));

  const list = units.data ?? [];
  const types = unitTypes.data ?? [];
  const openUnit = list.find((unit) => unit.id === openId) ?? null;

  async function submitCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const facility = units.facility;
    if (!facility) return;
    const data = new FormData(form);
    const code = String(data.get("unitCode") ?? "").trim();
    const typeId = Number(data.get("unitTypeId") ?? 0);
    const price = parsePrice(String(data.get("monthlyPrice") ?? ""));
    if (!code || !typeId) return;
    const outcome = await createAction.run("create", () =>
      createStorageUnit({
        facilityId: facility.id,
        code,
        unitTypeId: typeId,
        monthlyPrice: price,
        ...newLocation,
      }),
    );
    if (outcome.ok) {
      form.reset();
      setNewLocation(EMPTY_LOCATION);
      setCreating(false);
      setNotice(`Đã thêm khoang ${outcome.value.code}.`);
      units.reload();
    }
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Quản lý khoang chứa</h1>
          <p className="mt-1.5 text-small text-muted">
            Khoang vật lý tại {units.facility ? `cơ sở ${units.facility.name}` : "cơ sở của bạn"}.
            Giá riêng để trống thì khoang dùng giá của loại khoang.
          </p>
        </div>
        <Button
          onClick={() => {
            createAction.clear();
            setNewLocation(EMPTY_LOCATION);
            setCreating((value) => !value);
          }}
        >
          <Plus size={16} /> Thêm khoang
        </Button>
      </div>

      {creating && (
        <form onSubmit={submitCreate} className="step-enter bg-surface p-5">
          <SectionTitle>Khoang mới</SectionTitle>
          <div className="mt-4 grid grid-cols-3 gap-4 max-[760px]:grid-cols-1">
            <FormField label="Mã khoang" name="unitCode" placeholder="VD: A-05" required />
            <SelectField
              label="Loại khoang"
              name="unitTypeId"
              required
              placeholder="— Chọn loại —"
              options={types.map((type) => ({
                value: String(type.id),
                label: `${type.name} · ${formatMoney(type.monthlyPrice)}/tháng`,
              }))}
            />
            <FormField
              label="Giá riêng / tháng (tuỳ chọn)"
              name="monthlyPrice"
              type="number"
              placeholder="Để trống = giá loại khoang"
              suffix="đ"
            />
          </div>
          <div className="mt-2">
            <span className="mb-2 flex items-center gap-1.5 text-small font-semibold text-ink">
              <MapPin size={15} /> Vị trí trên sơ đồ (tuỳ chọn)
            </span>
            <LocationFields
              idPrefix="new-unit"
              floorCount={floorCount}
              value={newLocation}
              onChange={setNewLocation}
            />
          </div>
          {unitTypes.error && (
            <DemoNotice tone="error">
              Không tải được loại khoang: {unitTypes.error.message}
            </DemoNotice>
          )}
          {createAction.error && <DemoNotice tone="error">{createAction.error}</DemoNotice>}
          <div className="mt-2 flex items-center gap-2.5">
            <Button type="submit" pending={createAction.pending === "create"}>
              {createAction.pending !== "create" && <Check size={16} />} Lưu khoang
            </Button>
            <Button variant="secondary" onClick={() => setCreating(false)}>
              Huỷ
            </Button>
          </div>
        </form>
      )}

      <AccessOptionsSection facilityId={units.facility?.id ?? null} />

      <div className="grid grid-cols-[1fr_1fr_auto] items-end gap-3 max-[760px]:grid-cols-1">
        <FilterSelect label="Trạng thái" value={status} onChange={setStatus}>
          <option value="">Tất cả trạng thái</option>
          {STATUS_OPTIONS.map((value) => (
            <option key={value} value={value}>
              {labelOf(UNIT_STATUS_LABEL, value)}
            </option>
          ))}
        </FilterSelect>
        <FilterSelect label="Loại khoang" value={unitTypeId} onChange={setUnitTypeId}>
          <option value="">Tất cả loại khoang</option>
          {types.map((type) => (
            <option key={type.id} value={type.id}>
              {type.name}
            </option>
          ))}
        </FilterSelect>
        <div className="flex" role="group" aria-label="Kiểu hiển thị">
          <Button
            variant={view === "list" ? "primary" : "secondary"}
            aria-pressed={view === "list"}
            onClick={() => setView("list")}
          >
            <List size={16} /> Danh sách
          </Button>
          <Button
            variant={view === "map" ? "primary" : "secondary"}
            aria-pressed={view === "map"}
            onClick={() => setView("map")}
          >
            <LayoutGrid size={16} /> Sơ đồ
          </Button>
        </div>
      </div>

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}

      {units.error ? (
        <SurfaceState
          {...loadErrorState(units.error, "khoang chứa")}
          action={{ label: "Thử lại", onClick: units.reload }}
        />
      ) : !units.data ? (
        <SurfaceState variant="loading" title="Đang tải khoang chứa…" />
      ) : view === "map" ? (
        <div className="grid gap-3 bg-surface p-5">
          <p className="m-0 text-small text-muted">
            Bấm khoang để xem / sửa; bấm ô <strong>+</strong> để thêm khoang mới vào đúng vị trí đó.
            Bộ lọc trạng thái / loại áp dụng cho sơ đồ.
          </p>
          <FacilityLayoutDiagram
            floorCount={floorCount}
            units={list.filter(isPlaced).map((unit): DiagramUnit => ({
              code: unit.code,
              floorNo: unit.floorNo!,
              aisleSide: unit.aisleSide!,
              aislePosition: unit.aislePosition!,
              unitTypeName: unit.unitTypeName,
              status: unit.status,
            }))}
            onSelect={(picked) => {
              const match = list.find((unit) => unit.code === picked.code);
              if (match) {
                setNotice(null);
                setOpenId(match.id);
              }
            }}
            onEmptySlot={(slot) => {
              createAction.clear();
              setNewLocation(slot);
              setCreating(true);
              window.scrollTo({ top: 0, behavior: "smooth" });
            }}
          />
          {list.some((unit) => !isPlaced(unit)) && (
            <p className="m-0 text-small text-warning">
              Chưa xếp lên sơ đồ:{" "}
              {list
                .filter((unit) => !isPlaced(unit))
                .map((unit) => unit.code)
                .join(", ")}{" "}
              — mở từng khoang để đặt vị trí.
            </p>
          )}
        </div>
      ) : list.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Không có khoang nào phù hợp bộ lọc"
          description="Đổi bộ lọc, hoặc thêm khoang mới cho cơ sở."
        />
      ) : (
        <div className={`grid gap-2 ${units.loading ? "opacity-60" : ""}`}>
          {list.map((unit) => (
            <button
              key={unit.id}
              className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
              onClick={() => {
                setNotice(null);
                setOpenId(unit.id);
              }}
            >
              <div>
                <strong className="block text-[1rem] font-medium text-ink">{unit.code}</strong>
                <span className="mt-1 block text-label text-muted">
                  {unit.unitTypeName}
                  {unit.area !== null ? ` · ${unit.area} m²` : ""}
                  {unit.size ? ` · ${unit.size}` : ""} · {formatMoney(unit.monthlyPrice)}/tháng
                </span>
                <span
                  className={`mt-1 flex items-center gap-1 text-label ${isPlaced(unit) ? "text-muted" : "text-warning"}`}
                >
                  <MapPin size={12} />
                  {isPlaced(unit) ? locationText(unit) : "Chưa xếp lên sơ đồ"}
                </span>
              </div>
              <StatusTag tone={toneOf(UNIT_STATUS_TONE, unit.status)}>
                {labelOf(UNIT_STATUS_LABEL, unit.status)}
              </StatusTag>
            </button>
          ))}
          <p className="text-label text-muted">{list.length} khoang</p>
        </div>
      )}

      {openUnit && (
        <UnitPanel
          unit={openUnit}
          types={types}
          floorCount={floorCount}
          navigate={navigate}
          onClose={() => setOpenId(null)}
          onChanged={(message) => {
            setOpenId(null);
            setNotice(message);
            units.reload();
          }}
        />
      )}
    </div>
  );
}

function UnitPanel({
  unit,
  types,
  floorCount,
  navigate,
  onClose,
  onChanged,
}: {
  unit: StorageUnit;
  types: UnitType[];
  floorCount: number;
  navigate: Navigate;
  onClose: () => void;
  onChanged: (message: string) => void;
}) {
  const typePrice = types.find((type) => type.id === unit.unitTypeId)?.monthlyPrice;
  // `unitPrice` là giá riêng của khoang; null = đang dùng giá loại khoang.
  const [typeId, setTypeId] = useState(String(unit.unitTypeId));
  const [price, setPrice] = useState(unit.unitPrice === null ? "" : String(unit.unitPrice));
  const [location, setLocation] = useState<UnitLocationInput>({
    floorNo: unit.floorNo,
    aisleSide: unit.aisleSide,
    aislePosition: unit.aislePosition,
  });
  const action = useFmAction();

  async function saveLocation(next: UnitLocationInput) {
    const outcome = await action.run("location", () => setStorageUnitLocation(unit.id, next));
    if (outcome.ok) {
      onChanged(
        next.floorNo === null
          ? `Đã gỡ khoang ${unit.code} khỏi sơ đồ.`
          : `Đã đặt khoang ${unit.code}: ${locationText({
              floorNo: next.floorNo,
              aisleSide: next.aisleSide ?? "Left",
              aislePosition: next.aislePosition ?? 1,
            })}.`,
      );
    }
  }

  async function save() {
    const outcome = await action.run("save", () =>
      updateStorageUnit(unit.id, { unitTypeId: Number(typeId), monthlyPrice: parsePrice(price) }),
    );
    if (outcome.ok) onChanged(`Đã cập nhật khoang ${unit.code}.`);
  }

  async function toggleMaintenance(maintenance: boolean) {
    const outcome = await action.run("maintenance", () =>
      setStorageUnitMaintenance(unit.id, maintenance),
    );
    if (outcome.ok) {
      onChanged(
        maintenance
          ? `Đã chuyển khoang ${unit.code} sang bảo trì.`
          : `Đã mở lại khoang ${unit.code}.`,
      );
    }
  }

  async function holdMaintenance() {
    const outcome = await action.run("hold", () => holdStorageUnitMaintenance(unit.id));
    if (outcome.ok) onChanged(`Đã giữ khoang ${unit.code} ở trạng thái bảo trì.`);
  }

  // Bảo trì sau trả kho: cron tự mở lại sau số ngày cấu hình; FM có thể giữ bảo trì (thành bảo trì thủ công).
  const autoReopen = unit.status === "Maintenance" && unit.maintenanceStartedAt !== null;

  return (
    <DetailPanel title={unit.code} eyebrow={unit.unitTypeName} onClose={onClose}>
      <div className="grid gap-5">
        <div className="flex flex-wrap items-center gap-2 text-small text-muted">
          Trạng thái hiện tại:
          <StatusTag tone={toneOf(UNIT_STATUS_TONE, unit.status)}>
            {labelOf(UNIT_STATUS_LABEL, unit.status)}
          </StatusTag>
        </div>
        {unit.status === "Maintenance" && (
          <p className="m-0 text-label text-muted">
            {autoReopen
              ? `Bảo trì sau trả kho từ ${formatDateTime(unit.maintenanceStartedAt)} — hệ thống tự mở lại khi đủ số ngày bảo trì.`
              : "Bảo trì thủ công — khoang chỉ mở lại khi bạn bấm “Mở lại cho thuê”."}
          </p>
        )}
        <p className="m-0 text-small text-ink">
          Giá đang áp dụng: <strong>{formatMoney(unit.monthlyPrice)}/tháng</strong>
          {typePrice !== undefined && (
            <span className="block text-label text-muted">
              Giá loại khoang: {formatMoney(typePrice)}/tháng
            </span>
          )}
        </p>

        {action.error && <DemoNotice tone="error">{action.error}</DemoNotice>}

        <section className="bg-surface-subtle p-4">
          <SectionTitle>Loại &amp; giá riêng</SectionTitle>
          <div className="mt-3">
            <SelectField
              label="Loại khoang"
              name={`unit-type-${unit.id}`}
              value={typeId}
              onChange={setTypeId}
              placeholder="— Chọn loại —"
              options={[
                ...types.map((type) => ({ value: String(type.id), label: type.name })),
                ...(types.some((type) => type.id === unit.unitTypeId)
                  ? []
                  : [{ value: String(unit.unitTypeId), label: unit.unitTypeName }]),
              ]}
            />
            <label className={FIELD_LABEL} htmlFor={`unit-price-${unit.id}`}>
              Giá riêng / tháng
            </label>
            <div className={INPUT_WRAP}>
              <input
                id={`unit-price-${unit.id}`}
                type="number"
                min={0}
                className={INPUT_ELEMENT}
                value={price}
                onChange={(event) => setPrice(event.target.value)}
                placeholder="Để trống = giá loại khoang"
              />
              <span className="shrink-0 text-label text-muted">đ</span>
            </div>
          </div>
          <div className="mt-3">
            <Button pending={action.pending === "save"} disabled={!typeId} onClick={save}>
              {action.pending !== "save" && <Check size={16} />} Lưu thay đổi
            </Button>
          </div>
        </section>

        <section className="bg-surface-subtle p-4">
          <SectionTitle>
            <MapPin size={15} /> Vị trí trên sơ đồ
          </SectionTitle>
          <div className="mt-3">
            <LocationFields
              idPrefix={`unit-${unit.id}`}
              floorCount={floorCount}
              value={location}
              onChange={setLocation}
            />
          </div>
          <div className="mt-3 flex flex-wrap gap-2">
            <Button
              pending={action.pending === "location"}
              disabled={location.floorNo === null}
              onClick={() => saveLocation(location)}
            >
              {action.pending !== "location" && <Check size={16} />} Lưu vị trí
            </Button>
            {isPlaced(unit) && (
              <Button
                variant="secondary"
                disabled={action.pending !== null}
                onClick={() => saveLocation(EMPTY_LOCATION)}
              >
                Gỡ khỏi sơ đồ
              </Button>
            )}
          </div>
        </section>

        {unit.status === "Available" && (
          <Button
            variant="secondary"
            pending={action.pending === "maintenance"}
            onClick={() => toggleMaintenance(true)}
          >
            {action.pending !== "maintenance" && <Wrench size={16} />} Chuyển sang bảo trì
          </Button>
        )}
        {unit.status === "Maintenance" && (
          <Button
            variant="secondary"
            pending={action.pending === "maintenance"}
            onClick={() => toggleMaintenance(false)}
          >
            {action.pending !== "maintenance" && <RotateCcw size={16} />} Mở lại cho thuê
          </Button>
        )}
        {autoReopen && (
          <Button variant="secondary" pending={action.pending === "hold"} onClick={holdMaintenance}>
            {action.pending !== "hold" && <Lock size={16} />} Giữ bảo trì
          </Button>
        )}
        {unit.status === "Reserved" && (
          <Button variant="secondary" onClick={() => navigate("/fm/rental-orders")}>
            Xem đơn thuê của cơ sở <ArrowRight size={14} />
          </Button>
        )}
        {unit.status === "Rented" && (
          <Button variant="secondary" onClick={() => navigate("/fm/contracts")}>
            Xem hợp đồng của cơ sở <ArrowRight size={14} />
          </Button>
        )}
      </div>
    </DetailPanel>
  );
}

function AccessOptionsSection({ facilityId }: { facilityId: number | null }) {
  const [keyAccess, setKeyAccess] = useState(false);
  const [codeAccess, setCodeAccess] = useState(false);
  const [saved, setSaved] = useState<FacilityRow | null>(null);
  const action = useFmAction();

  useEffect(() => {
    if (facilityId === null) return;
    let active = true;
    fetchFacilityAccessOptions(facilityId)
      .then((current) => {
        if (!active) return;
        setSaved(current);
        setKeyAccess(current.keyAccess);
        setCodeAccess(current.codeAccess);
      })
      .catch(() => {
        // Không đọc được cấu hình hiện tại — vẫn cho chọn và lưu.
      });
    return () => {
      active = false;
    };
  }, [facilityId]);

  async function save() {
    if (facilityId === null) return;
    const outcome = await action.run("access", () =>
      updateFacilityAccessOptions(facilityId, { keyAccess, codeAccess }),
    );
    if (outcome.ok) {
      setSaved(outcome.value);
      setKeyAccess(outcome.value.keyAccess);
      setCodeAccess(outcome.value.codeAccess);
    }
  }

  return (
    <section className="bg-surface p-5">
      <SectionTitle>
        <KeyRound size={15} /> Hình thức truy cập khoang
      </SectionTitle>
      <p className="mt-2 text-label text-muted">
        {saved
          ? `Đang bật: ${[saved.keyAccess && "khóa cơ", saved.codeAccess && "mã số"].filter(Boolean).join(", ")}.`
          : "Cấu hình hiện tại chưa hiển thị ở đây — chọn các hình thức muốn bật rồi lưu để áp dụng."}{" "}
        Phải bật ít nhất một hình thức.
      </p>
      <div className="mt-3 flex flex-wrap items-center gap-5">
        <label className="flex items-center gap-2 text-small text-ink">
          <input
            type="checkbox"
            checked={keyAccess}
            onChange={(event) => setKeyAccess(event.target.checked)}
          />
          Khóa cơ (chìa)
        </label>
        <label className="flex items-center gap-2 text-small text-ink">
          <input
            type="checkbox"
            checked={codeAccess}
            onChange={(event) => setCodeAccess(event.target.checked)}
          />
          Mã số
        </label>
        <Button
          variant="secondary"
          size="sm"
          pending={action.pending === "access"}
          disabled={facilityId === null}
          onClick={save}
        >
          Lưu hình thức truy cập
        </Button>
      </div>
      {action.error && <DemoNotice tone="error">{action.error}</DemoNotice>}
    </section>
  );
}
