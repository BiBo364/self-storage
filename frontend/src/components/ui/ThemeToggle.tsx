import { Moon, Sun } from "lucide-react";

export type Theme = "dark" | "light";

export function ThemeToggle({
  theme,
  onToggle,
  className,
}: {
  theme: Theme;
  onToggle: () => void;
  className?: string;
}) {
  const isLight = theme === "light";

  return (
    <button
      className={`inline-grid h-[38px] w-[38px] place-items-center border border-border bg-surface text-muted transition-colors duration-150 hover:border-rule-strong hover:bg-surface-subtle hover:text-ink ${className ?? ""}`}
      type="button"
      role="switch"
      aria-checked={isLight}
      aria-label={isLight ? "Chuyển sang giao diện tối" : "Chuyển sang giao diện sáng"}
      title={isLight ? "Chuyển sang giao diện tối" : "Chuyển sang giao diện sáng"}
      onClick={onToggle}
    >
      {isLight ? <Moon size={17} strokeWidth={2} /> : <Sun size={17} strokeWidth={2} />}
    </button>
  );
}
