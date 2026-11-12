import { ArrowLeftRight, Minus, Plus, Trash2, Wand2 } from "lucide-react";
import { useState } from "react";
import { floorLetter, locationText, type AisleSide } from "../../app/facilityLayout";
import { Button } from "../ui/Button";
import { FIELD_LABEL } from "../ui/FormField";
import { FacilityLayoutDiagram } from "./FacilityLayoutDiagram";

export type BuilderUnit = {
  code: string;
  unitTypeId: number;
  floorNo: number;
  aisleSide: AisleSide;
  aislePosition: number;
};

const SELECT =
  "min-h-[40px] w-full rounded-[2px] border border-rule-strong bg-surface px-3 text-small text-ink outline-0 focus:border-brand";

const MAX_FLOORS = 20;

/** Mã kế tiếp của tầng: A-01, A-02… (tầng 2 → B-…), bỏ qua mã đã có. */
function nextCode(units: BuilderUnit[], floorNo: number): string {
  const taken = new Set(units.map((unit) => unit.code.toUpperCase()));
  const letter = floorLetter(floorNo);
  for (let n = 1; ; n += 1) {
    const code = `${letter}-${String(n).padStart(2, "0")}`;
    if (!taken.has(code)) return code;
  }
}

/**
 * Trình dựng sơ đồ lúc tạo cơ sở: số tầng, mỗi tầng bấm ô trống để thêm khoang (bên trái / phải lối đi, ô 1 sát cửa
 * vào), chọn khoang để đổi mã / loại / bên hoặc xoá, và điền nhanh N khoang mỗi bên.
 */
export function LayoutBuilder({
  floorCount,
  onFloorCountChange,
  units,
  onChange,
  unitTypes,
}: {
  floorCount: number;
  onFloorCountChange: (value: number) => void;
  units: BuilderUnit[];
  onChange: (units: BuilderUnit[]) => void;
  unitTypes: { id: number; name: string }[];
}) {
  const [floor, setFloor] = useState(1);
  const [defaultType, setDefaultType] = useState<number | null>(unitTypes[0]?.id ?? null);
  const [selected, setSelected] = useState<string | null>(null);
  const [perSide, setPerSide] = useState(4);
  const [hint, setHint] = useState<string | null>(null);
  const typeId = defaultType ?? unitTypes[0]?.id ?? null;
  const typeName = (id: number) => unitTypes.find((type) => type.id === id)?.name ?? "—";
  const current = units.find((unit) => unit.code === selected) ?? null;
  const highestFloor = units.reduce((max, unit) => Math.max(max, unit.floorNo), 0);

  function setFloors(next: number) {
    if (next < 1 || next > MAX_FLOORS) return;
    if (next < highestFloor) {
      setHint(`Tầng ${highestFloor} đang có khoang — xoá khoang ở tầng đó trước khi giảm số tầng.`);
      return;
    }
    setHint(null);
    onFloorCountChange(next);
    if (floor > next) setFloor(next);
  }

  function add(slot: { floorNo: number; aisleSide: AisleSide; aislePosition: number }) {
    if (typeId === null) {
      setHint("Chưa có loại khoang nào để thêm.");
      return;
    }
    const code = nextCode(units, slot.floorNo);
    onChange([...units, { ...slot, code, unitTypeId: typeId }]);
    setSelected(code);
    setHint(null);
  }

  function fillFloor() {
    if (typeId === null) return;
    const next = [...units];
    for (let position = 1; position <= perSide; position += 1) {
      for (const side of ["Left", "Right"] as AisleSide[]) {
        const taken = next.some(
          (unit) =>
            unit.floorNo === floor && unit.aisleSide === side && unit.aislePosition === position,
        );
        if (!taken) {
          next.push({
            code: nextCode(next, floor),
            unitTypeId: typeId,
            floorNo: floor,
            aisleSide: side,
            aislePosition: position,
          });
        }
      }
    }
    onChange(next);
    setHint(`Đã điền tầng ${floor}: ${perSide} ô mỗi bên (giữ nguyên khoang đã có).`);
  }

  function update(code: string, patch: Partial<BuilderUnit>) {
    if (patch.code !== undefined) {
      const clash = units.some(
        (unit) => unit.code !== code && unit.code.toUpperCase() === patch.code!.toUpperCase(),
      );
      if (clash) {
        setHint(`Mã ${patch.code} đã có trên sơ đồ.`);
        return;
      }
    }
    onChange(units.map((unit) => (unit.code === code ? { ...unit, ...patch } : unit)));
    if (patch.code !== undefined) setSelected(patch.code);
    setHint(null);
  }

  function flipSide(unit: BuilderUnit) {
    const side: AisleSide = unit.aisleSide === "Left" ? "Right" : "Left";
    const blocked = units.some(
      (other) =>
        other.floorNo === unit.floorNo &&
        other.aisleSide === side &&
        other.aislePosition === unit.aislePosition,
    );
    if (blocked) {
      setHint("Ô đối diện đã có khoang.");
      return;
    }
    update(unit.code, { aisleSide: side });
  }

  return (
    <div className="grid gap-4">
      <div className="grid grid-cols-[auto_minmax(0,1fr)] items-end gap-4 max-[560px]:grid-cols-1">
        <div>
          <span className={FIELD_LABEL}>Số tầng</span>
          <div className="flex items-center gap-1">
            <Button
              variant="secondary"
              size="sm"
              aria-label="Bớt một tầng"
              onClick={() => setFloors(floorCount - 1)}
              disabled={floorCount <= 1}
            >
              <Minus size={14} />
            </Button>
            <span className="min-w-[48px] text-center text-stat tabular-nums text-ink">
              {floorCount}
            </span>
            <Button
              variant="secondary"
              size="sm"
              aria-label="Thêm một tầng"
              onClick={() => setFloors(floorCount + 1)}
              disabled={floorCount >= MAX_FLOORS}
            >
              <Plus size={14} />
            </Button>
          </div>
        </div>
        <div>
          <label className={FIELD_LABEL} htmlFor="builder-type">
            Loại khoang khi thêm
          </label>
          <select
            id="builder-type"
            className={SELECT}
            value={typeId ?? ""}
            onChange={(event) => setDefaultType(Number(event.target.value))}
          >
            {unitTypes.map((type) => (
              <option key={type.id} value={type.id}>
                {type.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="flex flex-wrap items-end gap-2 bg-surface-subtle p-3">
        <div>
          <label className={FIELD_LABEL} htmlFor="builder-fill">
            Điền nhanh tầng {floor}: số ô mỗi bên
          </label>
          <input
            id="builder-fill"
            type="number"
            min={1}
            max={50}
            className={`${SELECT} max-w-[120px]`}
            value={perSide}
            onChange={(event) => setPerSide(Math.min(50, Math.max(1, Number(event.target.value))))}
          />
        </div>
        <Button variant="secondary" size="sm" onClick={fillFloor} disabled={typeId === null}>
          <Wand2 size={14} /> Điền tầng {floor}
        </Button>
        <span className="ml-auto text-small text-muted">
          Tổng: <strong className="text-ink">{units.length}</strong> khoang
        </span>
      </div>

      <p className="m-0 text-small text-muted">
        Bấm ô <strong>+</strong> để thêm khoang. Ô 1 nằm sát cửa vào; trái / phải tính khi đứng ở
        cửa nhìn vào trong.
      </p>

      <div className="grid grid-cols-[minmax(0,1fr)_260px] items-start gap-4 max-[760px]:grid-cols-1">
        <FacilityLayoutDiagram
          floorCount={floorCount}
          units={units.map((unit) => ({ ...unit, unitTypeName: typeName(unit.unitTypeId) }))}
          floor={floor}
          onFloorChange={setFloor}
          selectedCode={selected}
          onSelect={(unit) => setSelected(unit.code)}
          onEmptySlot={add}
        />
        <div className="border border-border bg-surface p-4">
          {current ? (
            <div key={current.code} className="step-enter grid gap-3">
              <span className="text-label text-muted">{locationText(current)}</span>
              <div>
                <label className={FIELD_LABEL} htmlFor="builder-code">
                  Mã khoang
                </label>
                <input
                  id="builder-code"
                  className={SELECT}
                  defaultValue={current.code}
                  maxLength={50}
                  onBlur={(event) => {
                    const value = event.target.value.trim().toUpperCase();
                    if (value && value !== current.code) update(current.code, { code: value });
                    else event.target.value = current.code;
                  }}
                />
              </div>
              <div>
                <label className={FIELD_LABEL} htmlFor="builder-unit-type">
                  Loại khoang
                </label>
                <select
                  id="builder-unit-type"
                  className={SELECT}
                  value={current.unitTypeId}
                  onChange={(event) =>
                    update(current.code, { unitTypeId: Number(event.target.value) })
                  }
                >
                  {unitTypes.map((type) => (
                    <option key={type.id} value={type.id}>
                      {type.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button variant="secondary" size="sm" onClick={() => flipSide(current)}>
                  <ArrowLeftRight size={14} /> Sang {current.aisleSide === "Left" ? "phải" : "trái"}
                </Button>
                <Button
                  variant="danger"
                  size="sm"
                  onClick={() => {
                    onChange(units.filter((unit) => unit.code !== current.code));
                    setSelected(null);
                  }}
                >
                  <Trash2 size={14} /> Xoá
                </Button>
              </div>
            </div>
          ) : (
            <p className="m-0 text-small text-muted">
              Chọn một khoang trên sơ đồ để đổi mã, loại, bên lối đi hoặc xoá.
            </p>
          )}
        </div>
      </div>

      {hint && <p className="m-0 text-small text-warning">{hint}</p>}
    </div>
  );
}
