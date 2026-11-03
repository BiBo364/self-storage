import { Check } from "lucide-react";
import type { ReactNode } from "react";

export function SuccessState({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="bg-surface px-6 py-10 text-center">
      <span className="inline-grid h-12 w-12 place-items-center bg-brand text-signal-ink">
        <Check size={24} aria-hidden="true" />
      </span>
      <h1 className="mx-auto mt-4 max-w-[18ch] text-title text-ink">{title}</h1>
      {description && (
        <p className="mx-auto mt-2 max-w-[430px] text-small leading-relaxed text-muted">
          {description}
        </p>
      )}
      {action}
    </div>
  );
}
