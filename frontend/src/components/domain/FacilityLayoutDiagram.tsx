import { DoorOpen, Lock, Plus } from "lucide-react";
import { useState } from "react";
import type { AisleSide } from "../../app/facilityLayout";

/**
 * Một khoang trên sơ đồ. `status` có khi người xem là FM / BOM; `available` có trên sơ đồ công khai (trang chọn kho).
 */
export type DiagramUnit = {
  code: string;
  floorNo: number;
  aisleSide: AisleSide;
  aislePosition: number;
  unitTypeName?: string | null;
  status?: string | null;
  available?: boolean | null;
};

/** Ô "khoang mới" đang chọn trong form thêm khoang — vẽ mờ, nhấp nháy để FM thấy trước vị trí. */
export type DiagramPreview = {
  floorNo: number;
  aisleSide: AisleSide;
  aislePosition: number;
  label?: string;
};

const STATUS_STYLE: Record<string, { label: string; cell: string; dot: string }> = {
  Available: {
    label: "Trống",
    cell: "border-success-line bg-success-soft text-success",
    dot: "bg-success",
  },
  Reserved: {
    label: "Đang giữ chỗ",
    cell: "border-warning-line bg-warning-soft text-warning",
    dot: "bg-warning",
  },
  Rented: { label: "Đang thuê", cell: "border-info-line bg-info-soft text-info", dot: "bg-info" },
  Maintenance: {
    label: "Bảo trì",
    cell: "border-danger-line bg-danger-soft text-danger",
    dot: "bg-danger",
  },
};

/** Màu theo loại khoang (sơ đồ cho khách): Small / Medium / Large cố định, loại khác xoay vòng. */
const TYPE_TONES = [
  { cell: "border-info-line bg-info-soft text-info", dot: "bg-info" },
  { cell: "border-brand bg-brand-soft text-ink", dot: "bg-brand" },
  { cell: "border-success-line bg-success-soft text-success", dot: "bg-success" },
  { cell: "border-warning-line bg-warning-soft text-warning", dot: "bg-warning" },
];
const KNOWN_TYPES = ["Small", "Medium", "Large"];

function typeTone(name: string | null | undefined, others: string[]) {
  const known = KNOWN_TYPES.indexOf(name ?? "");
  const index = known >= 0 ? known : KNOWN_TYPES.length + Math.max(0, others.indexOf(name ?? ""));
  return TYPE_TONES[index % TYPE_TONES.length];
}

const NEUTRAL_CELL = "border-border bg-surface-subtle text-muted";
const OCCUPIED_CELL =
  "border-border text-muted opacity-75 bg-[repeating-linear-gradient(135deg,var(--surface-subtle)_0_6px,transparent_6px_12px)]";
const HIGHLIGHT_CELL = "slot-highlight border-ink bg-brand text-signal-ink";

/**
 * Sơ đồ một cơ sở theo tầng: lối đi dọc ở giữa, cửa vào ở dưới; khoang xếp hai bên, ô 1 sát cửa vào.
 * - Xem: tô màu theo trạng thái (FM / BOM), theo loại + còn trống (`colorByType`, trang chọn kho) hoặc tô sáng
 *   `highlightCode` (khách / FS).
 * - Dựng: `onEmptySlot` để bấm ô trống thêm khoang, `onSelect` + `selectedCode` để chọn khoang đang sửa, `preview` để vẽ
 *   trước khoang mới đang nhập ở form.
 */
export function FacilityLayoutDiagram({
  floorCount,
  units,
  highlightCode,
  selectedCode,
  onSelect,
  onEmptySlot,
  floor: controlledFloor,
  onFloorChange,
  onlyFloor,
  minRows = 3,
  colorByType = false,
  preview,
}: {
  floorCount: number;
  units: DiagramUnit[];
  highlightCode?: string | null;
  selectedCode?: string | null;
  onSelect?: (unit: DiagramUnit) => void;
  onEmptySlot?: (slot: { floorNo: number; aisleSide: AisleSide; aislePosition: number }) => void;
  floor?: number;
  onFloorChange?: (floor: number) => void;
  /** Chỉ vẽ một tầng (thẻ vị trí khoang của khách / FS), không có thanh chọn tầng. */
  onlyFloor?: number;
  minRows?: number;
  colorByType?: boolean;
  preview?: DiagramPreview | null;
}) {
  const highlighted = units.find((unit) => unit.code === highlightCode);
  const [innerFloor, setInnerFloor] = useState(highlighted?.floorNo ?? preview?.floorNo ?? 1);
  const floor = onlyFloor ?? controlledFloor ?? innerFloor;
  const floors = Math.max(floorCount, 1);
  const editing = onEmptySlot !== undefined;
  const otherTypes = [
    ...new Set(
      units.map((unit) => unit.unitTypeName ?? "").filter((name) => !KNOWN_TYPES.includes(name)),
    ),
  ].sort();

  function pickFloor(next: number) {
    setInnerFloor(next);
    onFloorChange?.(next);
  }

  const onFloor = units.filter((unit) => unit.floorNo === floor);
  const previewHere = preview && preview.floorNo === floor ? preview : null;
  const deepest = Math.max(
    onFloor.reduce((max, unit) => Math.max(max, unit.aislePosition), 0),
    previewHere?.aislePosition ?? 0,
  );
  // Chế độ dựng luôn chừa thêm một hàng trống phía trong để nối dài dãy khoang.
  const rows = Math.max(minRows, deepest + (editing ? 1 : 0));
  const positions = Array.from({ length: rows }, (_, i) => rows - i); // xa cửa ở trên, sát cửa ở dưới
  const at = (side: AisleSide, position: number) =>
    onFloor.find((unit) => unit.aisleSide === side && unit.aislePosition === position);
  const showStatus = units.some((unit) => unit.status);
  const isPreview = (side: AisleSide, position: number) =>
    previewHere !== null &&
    previewHere.aisleSide === side &&
    previewHere.aislePosition === position;

  function cell(side: AisleSide, position: number) {
    const unit = at(side, position);
    if (!unit) {
      if (isPreview(side, position)) {
        return (
          <div className="slot-highlight pop-enter grid min-h-[58px] place-items-center rounded-lg border-2 border-dashed border-brand bg-brand-soft px-2 text-center text-ink">
            <span>
              <strong className="block font-display text-body leading-tight">
                {previewHere?.label || "Khoang mới"}
              </strong>
              <span className="block text-label opacity-80">vị trí đang chọn</span>
            </span>
          </div>
        );
      }
      return editing ? (
        <button
          type="button"
          className="grid min-h-[58px] place-items-center rounded-lg border border-dashed border-rule-strong bg-transparent text-muted hover:border-ink hover:bg-brand-soft hover:text-ink"
          onClick={() =>
            onEmptySlot?.({ floorNo: floor, aisleSide: side, aislePosition: position })
          }
          aria-label={`Thêm khoang tầng ${floor}, ${side === "Left" ? "trái" : "phải"}, ô ${position}`}
          title="Chọn ô này"
        >
          <Plus size={16} />
        </button>
      ) : (
        <div
          className="min-h-[58px] rounded-lg border border-dashed border-border opacity-60"
          aria-hidden
        />
      );
    }
    const isHighlight = unit.code === highlightCode;
    const isSelected = unit.code === selectedCode;
    const conflict = isPreview(side, position);
    const style = unit.status ? STATUS_STYLE[unit.status] : undefined;
    const occupied = colorByType && unit.available === false;
    const tone = isHighlight
      ? HIGHLIGHT_CELL
      : style
        ? style.cell
        : colorByType
          ? occupied
            ? OCCUPIED_CELL
            : typeTone(unit.unitTypeName, otherTypes).cell
          : NEUTRAL_CELL;
    const title = style
      ? `${unit.code} · ${style.label}`
      : colorByType
        ? `${unit.code} · ${unit.unitTypeName ?? ""} · ${occupied ? "đã có người thuê" : "còn trống"}`
        : unit.code;
    const content = (
      <>
        <strong className="flex items-center gap-1 font-display text-body leading-tight">
          {occupied && <Lock size={12} aria-hidden />}
          {unit.code}
        </strong>
        {unit.unitTypeName && (
          <span className="block truncate text-label opacity-80">
            {unit.unitTypeName}
            {colorByType ? (occupied ? " · đã thuê" : " · trống") : ""}
          </span>
        )}
      </>
    );
    const className = `pop-enter block min-h-[58px] w-full rounded-lg border-2 px-2 py-1.5 text-left ${tone} ${
      isSelected ? "outline outline-2 outline-offset-2 outline-ink" : ""
    } ${conflict ? "outline outline-2 outline-offset-2 outline-[var(--danger)]" : ""}`;
    return onSelect ? (
      <button
        type="button"
        className={`${className} hover:-translate-y-0.5 hover:shadow-[0_6px_14px_rgba(16,20,19,0.18)]`}
        onClick={() => onSelect(unit)}
        aria-pressed={isSelected}
        title={title}
      >
        {content}
      </button>
    ) : (
      <div className={className} title={title}>
        {content}
      </div>
    );
  }

  const typeLegend = colorByType
    ? [...new Set(units.map((unit) => unit.unitTypeName ?? "").filter(Boolean))].sort(
        (left, right) =>
          (KNOWN_TYPES.indexOf(left) + 1 || 99) - (KNOWN_TYPES.indexOf(right) + 1 || 99),
      )
    : [];

  return (
    <div className="grid gap-3">
      {onlyFloor === undefined && floors > 1 && (
        <div className="flex flex-wrap gap-1.5" role="tablist" aria-label="Chọn tầng">
          {Array.from({ length: floors }, (_, i) => i + 1).map((number) => {
            const onThisFloor = units.filter((unit) => unit.floorNo === number);
            const free = onThisFloor.filter((unit) => unit.available !== false).length;
            const active = number === floor;
            return (
              <button
                key={number}
                type="button"
                role="tab"
                aria-selected={active}
                className={`min-h-[36px] rounded-full border-2 px-3.5 text-small font-semibold ${
                  active
                    ? "border-ink bg-ink text-background"
                    : "border-border bg-surface text-ink hover:border-ink"
                }`}
                onClick={() => pickFloor(number)}
              >
                Tầng {number}
                <span className="ml-1.5 text-label opacity-70">
                  {colorByType ? `${free}/${onThisFloor.length} trống` : onThisFloor.length}
                </span>
              </button>
            );
          })}
        </div>
      )}

      <div key={floor} className="step-enter rounded-xl border border-border bg-surface p-3">
        <div className="mb-2 grid grid-cols-[minmax(0,1fr)_56px_minmax(0,1fr)] gap-2 text-label text-muted">
          <span>Bên trái</span>
          <span className="text-center">Tầng {floor}</span>
          <span className="text-right">Bên phải</span>
        </div>
        <div className="grid grid-cols-[minmax(0,1fr)_56px_minmax(0,1fr)] gap-2">
          {positions.map((position) => (
            <div key={position} className="contents">
              {cell("Left", position)}
              <div className="grid place-items-center rounded-md bg-[repeating-linear-gradient(180deg,var(--surface-subtle)_0_10px,transparent_10px_18px)] text-label text-muted">
                {position}
              </div>
              {cell("Right", position)}
            </div>
          ))}
          <div />
          <div className="grid place-items-center gap-0.5 rounded-md bg-ink py-2 text-background">
            <DoorOpen size={16} />
            <span className="text-[0.6875rem] font-semibold uppercase leading-none">Cửa vào</span>
          </div>
          <div />
        </div>
      </div>

      {showStatus && (
        <div className="flex flex-wrap gap-x-4 gap-y-1 text-label text-muted">
          {Object.entries(STATUS_STYLE).map(([key, style]) => (
            <span key={key} className="inline-flex items-center gap-1.5">
              <span className={`h-2.5 w-2.5 rounded-full ${style.dot}`} /> {style.label}
            </span>
          ))}
        </div>
      )}
      {colorByType && typeLegend.length > 0 && (
        <div className="flex flex-wrap gap-x-4 gap-y-1 text-label text-muted">
          {typeLegend.map((name) => (
            <span key={name} className="inline-flex items-center gap-1.5">
              <span className={`h-2.5 w-2.5 rounded-full ${typeTone(name, otherTypes).dot}`} />{" "}
              {name} còn trống
            </span>
          ))}
          <span className="inline-flex items-center gap-1.5">
            <Lock size={11} /> Đã có người thuê
          </span>
        </div>
      )}
    </div>
  );
}
