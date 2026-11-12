import { ChevronLeft, ChevronRight, ExternalLink, X } from "lucide-react";
import { useEffect, useState } from "react";
import { useModalA11y } from "./useModalA11y";

/**
 * Xem ảnh cỡ lớn trên nền tối. ← / → chuyển ảnh, Esc hoặc bấm nền để đóng.
 * Dùng cho ảnh giấy tờ / hiện trạng — FS cần nhìn rõ chữ trên CCCD khi đối chiếu.
 */
export function ImageLightbox({
  images,
  startIndex = 0,
  onClose,
}: {
  images: { url: string; name: string }[];
  startIndex?: number;
  onClose: () => void;
}) {
  const [index, setIndex] = useState(startIndex);
  const containerRef = useModalA11y<HTMLDivElement>(onClose);
  const count = images.length;
  const current = images[Math.min(index, count - 1)];

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "ArrowRight") setIndex((value) => (value + 1) % count);
      if (event.key === "ArrowLeft") setIndex((value) => (value - 1 + count) % count);
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [count]);

  if (!current) return null;

  const NAV_BUTTON =
    "grid h-12 w-12 shrink-0 place-items-center border border-[rgba(233,234,230,0.28)] bg-[rgba(16,20,19,0.55)] text-[#e9eae6] hover:border-[#e9eae6] hover:bg-[rgba(233,234,230,0.12)]";

  return (
    <div
      className="fade-enter fixed inset-0 z-[95] flex flex-col bg-[rgba(7,10,9,0.9)] p-4"
      role="presentation"
      onClick={onClose}
    >
      <div
        ref={containerRef}
        tabIndex={-1}
        className="flex min-h-0 flex-1 flex-col focus:outline-none"
        role="dialog"
        aria-modal="true"
        aria-label={`Ảnh ${index + 1}/${count}`}
      >
        <div
          className="flex items-center justify-between gap-3 text-[#e9eae6]"
          onClick={(event) => event.stopPropagation()}
        >
          <span className="truncate text-small">
            {index + 1}/{count} · {current.name}
          </span>
          <div className="flex gap-2">
            <a
              href={current.url}
              target="_blank"
              rel="noreferrer"
              className={NAV_BUTTON}
              aria-label="Mở ảnh gốc ở tab mới"
              title="Mở ảnh gốc"
            >
              <ExternalLink size={18} />
            </a>
            <button type="button" className={NAV_BUTTON} onClick={onClose} aria-label="Đóng">
              <X size={20} />
            </button>
          </div>
        </div>
        <div className="flex min-h-0 flex-1 items-center justify-center gap-3 py-4">
          {count > 1 && (
            <button
              type="button"
              className={NAV_BUTTON}
              onClick={(event) => {
                event.stopPropagation();
                setIndex((value) => (value - 1 + count) % count);
              }}
              aria-label="Ảnh trước"
            >
              <ChevronLeft size={22} />
            </button>
          )}
          <img
            key={current.url}
            src={current.url}
            alt={current.name}
            className="pop-enter max-h-full min-h-0 max-w-full object-contain shadow-[0_24px_60px_rgba(0,0,0,0.5)]"
            onClick={(event) => event.stopPropagation()}
          />
          {count > 1 && (
            <button
              type="button"
              className={NAV_BUTTON}
              onClick={(event) => {
                event.stopPropagation();
                setIndex((value) => (value + 1) % count);
              }}
              aria-label="Ảnh sau"
            >
              <ChevronRight size={22} />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
