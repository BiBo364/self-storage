import { X } from "lucide-react";
import { Button } from "./Button";
import { useId } from "react";
import { useModalA11y } from "./useModalA11y";

export function ComingSoonDialog({ onClose }: { onClose: () => void }) {
  const titleId = useId();
  const containerRef = useModalA11y<HTMLDivElement>(onClose);

  return (
    <div
      className="fixed inset-0 z-[80] grid place-items-center bg-[rgba(7,16,19,0.68)] p-5"
      role="presentation"
      onClick={onClose}
    >
      <div
        ref={containerRef}
        tabIndex={-1}
        className="relative w-[min(420px,100%)] border border-[var(--container-border)] bg-surface p-6 shadow-[var(--shadow-soft)] focus:outline-none"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(event) => event.stopPropagation()}
      >
        <button
          className="absolute right-4 top-4 grid h-9 w-9 place-items-center border-0 bg-transparent text-muted hover:text-ink"
          onClick={onClose}
          aria-label="Đóng"
        >
          <X size={18} />
        </button>
        <p className="text-label text-muted">Đang hoàn thiện</p>
        <h2 id={titleId} className="mb-3 mt-2 max-w-[300px] text-section text-ink">
          Chức năng đang được hoàn thiện
        </h2>
        <p className="mb-6 text-small text-muted">
          Tính năng này sẽ sớm có mặt trong không gian của bạn.
        </p>
        <Button className="w-full" onClick={onClose}>
          Đã hiểu
        </Button>
      </div>
    </div>
  );
}
