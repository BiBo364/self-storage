import { Loader2 } from "lucide-react";
import { NoticeTone } from "../../app/types";

const TONE_CLASSES: Record<NoticeTone, string> = {
  info: "text-info bg-info-soft border-info-line",
  success: "text-success bg-success-soft border-success-line",
  error: "text-danger bg-danger-soft border-danger-line",
  pending: "text-muted bg-surface-subtle border-border",
};

export function DemoNotice({ tone, children }: { tone: NoticeTone; children: React.ReactNode }) {
  return (
    <div
      className={`mt-4 flex items-start gap-2 rounded-none border px-3 py-2.5 text-label leading-normal ${TONE_CLASSES[tone]}`}
      role={tone === "error" ? "alert" : "status"}
    >
      {tone === "pending" ? (
        <Loader2
          className="mt-0.5 shrink-0 animate-[surface-state-spin_0.9s_linear_infinite]"
          size={12}
        />
      ) : (
        <span className="mt-[5px] h-[7px] w-[7px] shrink-0 rounded-full bg-current" />
      )}
      {children}
    </div>
  );
}
