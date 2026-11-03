import { X } from "lucide-react";
import { useId, type ReactNode } from "react";
import { useModalA11y } from "./useModalA11y";

/**
 * Shared slide-over panel for record detail. It follows the Bãi kho system:
 * a surface panel with a container border, no rounded corners and no internal
 * divider lines; sections are separated by spacing.
 */
export function DetailPanel({
  title,
  eyebrow,
  onClose,
  children,
  footer,
}: {
  title: string;
  eyebrow?: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
}) {
  const titleId = useId();
  const containerRef = useModalA11y<HTMLElement>(onClose);

  return (
    <div
      className="fixed inset-0 z-[70] flex justify-end bg-[rgba(16,20,19,0.55)]"
      role="presentation"
      onClick={onClose}
    >
      <aside
        ref={containerRef}
        tabIndex={-1}
        className="flex h-full w-[min(480px,100%)] flex-col border-l border-[var(--container-border)] bg-surface shadow-[var(--shadow-soft)] max-[760px]:w-full focus:outline-none"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(event) => event.stopPropagation()}
      >
        <header className="flex items-start justify-between gap-4 p-5">
          <div>
            {eyebrow && <p className="text-label text-muted">{eyebrow}</p>}
            <h2 id={titleId} className="mt-1 text-section text-ink">
              {title}
            </h2>
          </div>
          <button
            className="grid h-9 w-9 place-items-center border-0 bg-transparent text-muted hover:text-ink"
            onClick={onClose}
            aria-label="Đóng"
          >
            <X size={19} />
          </button>
        </header>
        <div className="flex-1 overflow-y-auto px-5 pb-5">{children}</div>
        {footer && <footer className="flex justify-end gap-2.5 p-5">{footer}</footer>}
      </aside>
    </div>
  );
}
