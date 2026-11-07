const pad = (value: number) => String(value).padStart(2, "0");

/** 1.500.000 đ */
export function formatMoney(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  return `${new Intl.NumberFormat("vi-VN").format(Number(value))} đ`;
}

/** dd/MM/yyyy — nhận ISO date (yyyy-MM-dd) hoặc ISO instant. */
export function formatDate(value: string | null | undefined): string {
  if (!value) return "—";
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const [y, m, d] = value.split("-");
    return `${d}/${m}/${y}`;
  }
  const date = new Date(value);
  return `${pad(date.getDate())}/${pad(date.getMonth() + 1)}/${date.getFullYear()}`;
}

/** HH:mm dd/MM/yyyy */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  return `${pad(date.getHours())}:${pad(date.getMinutes())} ${formatDate(value)}`;
}

/** HH:mm */
export function formatTime(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** yyyy-MM-dd theo giờ máy (cho input type="date"). */
export function isoDate(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
