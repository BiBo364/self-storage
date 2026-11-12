import { DoorOpen, Plus } from "lucide-react";
import { useState } from "react";
import type { AisleSide } from "../../app/facilityLayout";

/** Một khoang trên sơ đồ. `status` có khi người xem là FM / BOM. */
export type DiagramUnit = {
  code: string;
  floorNo: number;
  aisleSide: AisleSide;
  aislePosition: number;
  unitTypeName?: string | null;
  status?: string | null;
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

const NEUTRAL_CELL = "border-border bg-surface-subtle text-muted";
const HIGHLIGHT_CELL = "slot-highlight border-ink bg-brand text-signal-ink";

/**
 * Sơ đồ một cơ sở theo tầng: lối đi dọc ở giữa, cửa vào ở dưới; khoang xếp hai bên, ô 1 sát cửa vào.
 * - Xem: tô màu theo trạng thái (FM / BOM) hoặc tô sáng `highlightCode` (khách / FS).
 * - Dựng: `onEmptySlot` để bấm ô trống thêm khoang, `onSelect` + `selectedCode` để chọn khoang đang sửa.
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
}) {
  const highlighted = units.find((unit) => unit.code === highlightCode);
  const [innerFloor, setInnerFloor] = useState(highlighted?.floorNo ?? 1);
  const floor = onlyFloor ?? controlledFloor ?? innerFloor;
  const floors = Math.max(floorCount, 1);
  const editing = onEmptySlot !== undefined;

  function pickFloor(next: number) {
    setInnerFloor(next);
    onFloorChange?.(next);
  }

  const onFloor = units.filter((unit) => unit.floorNo === floor);
  const deepest = onFloor.reduce((max, unit) => Math.max(max, unit.aislePosition), 0);
  // Chế độ dựng luôn chừa thêm một hàng trống phía trong để nối dài dãy khoang.
  const rows = Math.max(minRows, deepest + (editing ? 1 : 0));
  const positions = Array.from({ length: rows }, (_, i) => rows - i); // xa cửa ở trên, sát cửa ở dưới
  const at = (side: AisleSide, position: number) =>
    onFloor.find((unit) => unit.aisleSide === side && unit.aislePosition === position);
  const showStatus = units.some((unit) => unit.status);

  function cell(side: AisleSide, position: number) {
    const unit = at(side, position);
    if (!unit) {
      return editing ? (
        <button
          type="button"
          className="grid min-h-[58px] place-items-center border border-dashed border-rule-strong bg-transparent text-muted hover:border-ink hover:bg-brand-soft hover:text-ink"
          onClick={() =>
            onEmptySlot?.({ floorNo: floor, aisleSide: side, aislePosition: position })
          }
          aria-label={`Thêm khoang tầng ${floor}, ${side === "Left" ? "trái" : "phải"}, ô ${position}`}
          title="Thêm khoang vào ô này"
        >
          <Plus size={16} />
        </button>
      ) : (
        <div className="min-h-[58px] border border-dashed border-border opacity-60" aria-hidden />
      );
    }
    const isHighlight = unit.code === highlightCode;
    const isSelected = unit.code === selectedCode;
    const style = unit.status ? STATUS_STYLE[unit.status] : undefined;
    const tone = isHighlight ? HIGHLIGHT_CELL : (style?.cell ?? NEUTRAL_CELL);
    const content = (
      <>
        <strong className="block font-display text-body leading-tight">{unit.code}</strong>
        {unit.unitTypeName && (
          <span className="block truncate text-label opacity-80">{unit.unitTypeName}</span>
        )}
      </>
    );
    const className = `pop-enter block min-h-[58px] w-full border-2 px-2 py-1.5 text-left ${tone} ${
      isSelected ? "outline outline-2 outline-offset-2 outline-ink" : ""
    }`;
    return onSelect ? (
      <button
        type="button"
        className={`${className} hover:-translate-y-0.5 hover:shadow-[0_6px_14px_rgba(16,20,19,0.18)]`}
        onClick={() => onSelect(unit)}
        aria-pressed={isSelected}
        title={style ? `${unit.code} · ${style.label}` : unit.code}
      >
        {content}
      </button>
    ) : (
      <div className={className} title={style ? `${unit.code} · ${style.label}` : unit.code}>
        {content}
      </div>
    );
  }

  return (
    <div className="grid gap-3">
      {onlyFloor === undefined && floors > 1 && (
        <div className="flex flex-wrap gap-1.5" role="tablist" aria-label="Chọn tầng">
          {Array.from({ length: floors }, (_, i) => i + 1).map((number) => {
            const count = units.filter((unit) => unit.floorNo === number).length;
            const active = number === floor;
            return (
              <button
                key={number}
                type="button"
                role="tab"
                aria-selected={active}
                className={`min-h-[36px] border-2 px-3 text-small font-semibold ${
                  active
                    ? "border-ink bg-ink text-background"
                    : "border-border bg-surface text-ink hover:border-ink"
                }`}
                onClick={() => pickFloor(number)}
              >
                Tầng {number}
                <span className="ml-1.5 text-label opacity-70">{count}</span>
              </button>
            );
          })}
        </div>
      )}

      <div key={floor} className="step-enter border border-border bg-surface p-3">
        <div className="mb-2 grid grid-cols-[minmax(0,1fr)_56px_minmax(0,1fr)] gap-2 text-label text-muted">
          <span>Bên trái</span>
          <span className="text-center">Tầng {floor}</span>
          <span className="text-right">Bên phải</span>
        </div>
        <div className="grid grid-cols-[minmax(0,1fr)_56px_minmax(0,1fr)] gap-2">
          {positions.map((position) => (
            <div key={position} className="contents">
              {cell("Left", position)}
              <div className="grid place-items-center bg-[repeating-linear-gradient(180deg,var(--surface-subtle)_0_10px,transparent_10px_18px)] text-label text-muted">
                {position}
              </div>
              {cell("Right", position)}
            </div>
          ))}
          <div />
          <div className="grid place-items-center gap-0.5 bg-ink py-2 text-background">
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
              <span className={`h-2.5 w-2.5 ${style.dot}`} /> {style.label}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
