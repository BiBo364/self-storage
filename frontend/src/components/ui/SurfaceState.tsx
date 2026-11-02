import { AlertTriangle, Inbox, Loader2, Lock, LogOut, SearchX } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "./Button";

export type SurfaceStateVariant =
  "loading" | "empty" | "error" | "forbidden" | "session-expired" | "not-found";

const SPIN = "animate-[surface-state-spin_0.9s_linear_infinite]";

const ICON_BY_VARIANT: Record<SurfaceStateVariant, ReactNode> = {
  loading: <Loader2 className={SPIN} size={24} />,
  empty: <Inbox size={24} />,
  error: <AlertTriangle size={24} />,
  forbidden: <Lock size={24} />,
  "session-expired": <LogOut size={24} />,
  "not-found": <SearchX size={24} />,
};

/**
 * Shared surface state. It uses the same block rhythm as the rest of the
 * product: a surface, a small square icon tile and one concrete next action.
 */
export function SurfaceState({
  variant,
  title,
  description,
  action,
}: {
  variant: SurfaceStateVariant;
  title: string;
  description?: string;
  action?: { label: string; onClick: () => void };
}) {
  const isNegative = variant === "error" || variant === "forbidden";
  return (
    <div
      className="grid justify-items-center gap-2.5 bg-surface px-6 py-12 text-center"
      role={isNegative ? "alert" : "status"}
    >
      <div
        className={`grid h-11 w-11 place-items-center bg-surface-subtle ${isNegative ? "text-danger" : "text-muted"}`}
      >
        {ICON_BY_VARIANT[variant]}
      </div>
      <p className="m-0 text-section text-ink">{title}</p>
      {description && (
        <p className="m-0 max-w-[380px] text-small leading-relaxed text-muted">{description}</p>
      )}
      {action && (
        <Button variant="secondary" className="mt-2" onClick={action.onClick}>
          {action.label}
        </Button>
      )}
    </div>
  );
}
