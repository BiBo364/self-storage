type BrandProps = {
  className?: string;
  /** Public header shrinks the mark/text once the page has scrolled. */
  compact?: boolean;
  tone?: "default" | "onDark";
  onClick: () => void;
};

export function Brand({ className = "", compact = false, tone = "default", onClick }: BrandProps) {
  return (
    <button
      className={`inline-flex items-center gap-2.5 border-0 bg-transparent p-0 font-display font-semibold tracking-[0.01em] ${tone === "onDark" ? "text-[var(--on-dark-ink)]" : "text-ink"} ${
        compact ? "text-[15px]" : "text-[16px]"
      } ${className}`}
      onClick={onClick}
      aria-label="Về trang chủ Kho Mộc"
    >
      <svg
        className={compact ? "h-6 w-6" : "h-7 w-7"}
        viewBox="0 0 28 28"
        fill="currentColor"
        aria-hidden="true"
      >
        <rect x="2" y="5" width="24" height="4" />
        <rect x="2" y="12" width="17" height="4" />
        <rect x="9" y="19" width="17" height="4" />
      </svg>
      <span>Kho Mộc</span>
    </button>
  );
}
