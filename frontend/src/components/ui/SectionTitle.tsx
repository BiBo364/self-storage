import type { ReactNode } from "react";

export function SectionTitle({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <h2 className="flex items-center gap-2 text-[15px] font-semibold text-ink">
        <span className="h-2 w-2 bg-brand" aria-hidden="true" />
        {children}
      </h2>
      {action}
    </div>
  );
}
