import { useEffect } from "react";
import { ArrowRight, X } from "lucide-react";
import { formatPrice, UnitType } from "../../mocks/catalog";
import { Navigate } from "../../app/types";
import { Button } from "../ui/Button";

export function UnitDetailsDialog({
  unit,
  onClose,
  navigate,
}: {
  unit: UnitType;
  onClose: () => void;
  navigate: Navigate;
}) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-[rgba(16,20,19,0.6)] p-5"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        className="relative w-[min(560px,100%)] border border-[var(--container-border)] bg-surface p-6 shadow-[var(--shadow-soft)]"
        role="dialog"
        aria-modal="true"
        aria-labelledby="unit-dialog-title"
      >
        <button
          className="absolute right-4 top-4 grid h-9 w-9 place-items-center border-0 bg-transparent text-muted hover:text-ink"
          onClick={onClose}
          aria-label="Đóng"
        >
          <X size={19} />
        </button>
        <span className="text-label text-muted">Phương án lưu trữ</span>
        <h2 id="unit-dialog-title" className="mt-1 pr-10 text-section text-ink">
          {unit.name}
        </h2>

        <div className="mt-5 flex items-stretch gap-5 max-[560px]:block">
          <div className="grid h-24 w-24 shrink-0 place-items-center border-2 border-[#101413] bg-brand text-stat font-semibold text-signal-ink max-[560px]:mb-4 max-[560px]:h-16 max-[560px]:w-16">
            {unit.area}
          </div>
          <div className="grid flex-1 grid-cols-2 gap-3 max-[560px]:grid-cols-1">
            <div>
              <span className="text-label text-muted">Quy mô</span>
              <strong className="block text-small font-semibold text-ink">{unit.capacity}</strong>
            </div>
            <div>
              <span className="text-label text-muted">Thông số mẫu</span>
              <strong className="block text-small font-semibold text-ink">{unit.size}</strong>
            </div>
            <div className="col-span-2 max-[560px]:col-span-1">
              <span className="text-label text-muted">Giá tham khảo</span>
              <strong className="block text-stat text-ink">
                {formatPrice(unit.monthlyPrice)} đ
                <span className="ml-1 text-label font-normal text-muted">/ tháng</span>
              </strong>
            </div>
          </div>
        </div>

        <p className="mt-5 text-small leading-relaxed text-muted">{unit.description}</p>
        <p className="mt-2 text-small leading-relaxed text-ink">
          Phù hợp: {unit.idealFor.toLowerCase()}.
        </p>

        <Button
          className="mt-5 w-full"
          onClick={() => {
            onClose();
            navigate("/rental-requests/new");
          }}
        >
          Gửi yêu cầu loại kho này <ArrowRight size={16} />
        </Button>
      </div>
    </div>
  );
}
