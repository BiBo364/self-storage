export function formatPrice(value: number) {
  return new Intl.NumberFormat("vi-VN").format(value);
}

export function formatMoney(value: number) {
  return `${formatPrice(value)} đ`;
}

export function formatDate(iso: string) {
  const date = new Date(iso);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${pad(date.getDate())}/${pad(date.getMonth() + 1)}/${date.getFullYear()}`;
}
