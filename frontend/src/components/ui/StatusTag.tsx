type StatusTone = "success" | "warning" | "danger" | "info" | "neutral";

const TONE_CLASS: Record<StatusTone, string> = {
  success: "bg-success-soft text-success",
  warning: "bg-warning-soft text-warning",
  danger: "bg-danger-soft text-danger",
  info: "bg-info-soft text-info",
  neutral: "bg-surface-subtle text-muted",
};

export function StatusTag({ tone, children }: { tone: StatusTone; children: React.ReactNode }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 px-2 py-1 text-label font-semibold ${TONE_CLASS[tone]}`}
    >
      <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />
      {children}
    </span>
  );
}
