import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";

/**
 * Hiện dần khi cuộn tới: gắn `.is-visible` lần đầu phần tử vào khung nhìn (CSS `.reveal` trong styles.css).
 * `delay` (giây) để các khối cạnh nhau hiện lần lượt. Trình duyệt không có IntersectionObserver thì hiện ngay.
 */
export function Reveal({
  children,
  delay = 0,
  className = "",
  as: Tag = "div",
}: {
  children: ReactNode;
  delay?: number;
  className?: string;
  as?: "div" | "section" | "article" | "li";
}) {
  const ref = useRef<HTMLElement | null>(null);
  const [visible, setVisible] = useState(() => typeof IntersectionObserver === "undefined");

  useEffect(() => {
    const element = ref.current;
    if (!element || visible) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setVisible(true);
          observer.disconnect();
        }
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.08 },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [visible]);

  const style = { "--reveal-delay": `${delay}s` } as CSSProperties;
  return (
    <Tag
      ref={(node: HTMLElement | null) => {
        ref.current = node;
      }}
      className={`reveal ${visible ? "is-visible" : ""} ${className}`}
      style={style}
    >
      {children}
    </Tag>
  );
}

/** Số đếm lên từ 0 trong `duration` ms khi giá trị đổi (thống kê ở hero); giảm chuyển động thì hiện số ngay. */
export function CountUp({
  value,
  pad = 0,
  duration = 900,
}: {
  value: number;
  pad?: number;
  duration?: number;
}) {
  const [shown, setShown] = useState(0);
  const reduce =
    typeof window !== "undefined" &&
    window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;

  useEffect(() => {
    if (reduce) return;
    let frame = 0;
    const start = performance.now();
    const tick = (now: number) => {
      const progress = Math.min(1, (now - start) / duration);
      setShown(Math.round(value * (1 - Math.pow(1 - progress, 3))));
      if (progress < 1) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [value, duration, reduce]);

  return <>{String(reduce ? value : shown).padStart(pad, "0")}</>;
}
