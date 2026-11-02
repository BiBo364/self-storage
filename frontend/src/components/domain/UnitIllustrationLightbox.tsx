import { Minus, Plus, RotateCcw, X } from "lucide-react";
import { useEffect, useState } from "react";
import { Button } from "../ui/Button";
import { UnitIllustration } from "./UnitIllustration";

type UnitIllustrationLightboxProps = {
  unitCode: string;
  unitTypeName: string;
  area?: number;
  width?: number;
  depth?: number;
  height?: number;
  onClose: () => void;
};

const MIN_ZOOM = 1;
const MAX_ZOOM = 3;
const STEP = 0.25;

export function UnitIllustrationLightbox({
  unitCode,
  unitTypeName,
  area,
  width,
  depth,
  height,
  onClose,
}: UnitIllustrationLightboxProps) {
  const [zoom, setZoom] = useState(1);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-[80] flex flex-col bg-[rgba(16,20,19,0.94)] p-4 sm:p-6"
      role="dialog"
      aria-modal="true"
      aria-labelledby="unit-lightbox-title"
    >
      <div className="flex items-start justify-between gap-4">
        <div>
          <span className="text-label text-white/55">Hình minh hoạ khoang</span>
          <h2 id="unit-lightbox-title" className="mt-1 text-section text-white">
            {unitCode} · {unitTypeName}
          </h2>
        </div>
        <button
          className="grid h-10 w-10 place-items-center border border-white/25 bg-transparent text-white hover:bg-white/10"
          onClick={onClose}
          aria-label="Đóng"
        >
          <X size={20} />
        </button>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-2">
        <Button
          variant="onDark"
          size="sm"
          disabled={zoom <= MIN_ZOOM}
          onClick={() => setZoom((value) => Math.max(MIN_ZOOM, value - STEP))}
          aria-label="Thu nhỏ"
        >
          <Minus size={16} />
        </Button>
        <span className="w-14 text-center text-label text-white/70">{Math.round(zoom * 100)}%</span>
        <Button
          variant="onDark"
          size="sm"
          disabled={zoom >= MAX_ZOOM}
          onClick={() => setZoom((value) => Math.min(MAX_ZOOM, value + STEP))}
          aria-label="Phóng to"
        >
          <Plus size={16} />
        </Button>
        <Button variant="onDark" size="sm" onClick={() => setZoom(1)}>
          <RotateCcw size={15} /> 100%
        </Button>
        <span className="text-label text-white/45">Cuộn để xem phần bị cắt</span>
      </div>

      <div className="mt-4 flex-1 overflow-auto">
        <div className="mx-auto" style={{ width: `${zoom * 100}%`, minWidth: "100%" }}>
          <UnitIllustration
            unitCode={unitCode}
            unitTypeName={unitTypeName}
            area={area}
            width={width}
            depth={depth}
            height={height}
          />
        </div>
      </div>

      <p className="mt-2 text-center text-label text-white/45">
        Hình minh hoạ — khoang thực tế có thể khác.
      </p>
    </div>
  );
}
