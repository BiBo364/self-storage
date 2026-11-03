import { useId } from "react";
import { Button } from "./Button";
import { useModalA11y } from "./useModalA11y";

/**
 * Shared destructive/blocking confirmation dialog. Square surface, container
 * border, one primary action and one escape. No nested divider lines.
 */
export function ConfirmDialog({
  title,
  description,
  confirmLabel,
  cancelLabel = "Hủy",
  tone = "danger",
  isPending = false,
  onConfirm,
  onCancel,
}: {
  title: string;
  description?: string;
  confirmLabel: string;
  cancelLabel?: string;
  tone?: "danger" | "default";
  isPending?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const titleId = useId();
  const containerRef = useModalA11y<HTMLDivElement>(onCancel);

  return (
    <div
      className="fixed inset-0 z-[90] grid place-items-center bg-[rgba(16,20,19,0.6)] p-5"
      role="presentation"
      onClick={onCancel}
    >
      <div
        ref={containerRef}
        tabIndex={-1}
        className="w-[min(420px,100%)] border border-[var(--container-border)] bg-surface p-6 shadow-[var(--shadow-soft)] focus:outline-none"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(event) => event.stopPropagation()}
      >
        <h2 id={titleId} className="text-section text-ink">
          {title}
        </h2>
        {description && <p className="mt-2 text-small leading-relaxed text-muted">{description}</p>}
        <div className="mt-5 flex justify-end gap-2.5">
          <Button variant="secondary" onClick={onCancel} disabled={isPending}>
            {cancelLabel}
          </Button>
          <Button
            variant={tone === "danger" ? "danger" : "primary"}
            pending={isPending}
            onClick={onConfirm}
          >
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
