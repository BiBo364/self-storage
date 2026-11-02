import { ChevronDown } from "lucide-react";
import type { ReactNode } from "react";
import { INPUT_ELEMENT, INPUT_WRAP } from "./FormField";

export function FilterSelect({
  label,
  value,
  onChange,
  children,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  children: ReactNode;
}) {
  return (
    <label className="grid gap-1.5">
      <span className="text-label text-muted">{label}</span>
      <div className={INPUT_WRAP}>
        <select
          className={`${INPUT_ELEMENT} pr-8`}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        >
          {children}
        </select>
        <ChevronDown size={15} className="pointer-events-none absolute right-3 text-muted" />
      </div>
    </label>
  );
}
