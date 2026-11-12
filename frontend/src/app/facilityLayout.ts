import { apiRequest } from "./api";

// Sơ đồ cơ sở (V34): mỗi khoang nằm ở một tầng, một bên lối đi (nhìn từ cửa vào) và thứ tự tính từ cửa vào.

export type AisleSide = "Left" | "Right";

export type UnitPlacement = {
  floorNo: number;
  aisleSide: AisleSide;
  aislePosition: number;
};

/** GET /api/facilities/{id}/layout — FacilityLayoutView.Slot; `available` = khoang còn nhận thuê. */
export type LayoutSlot = UnitPlacement & { code: string; unitTypeName: string; available: boolean };

export type FacilityLayout = {
  facilityId: number;
  code: string;
  name: string;
  floorCount: number;
  units: LayoutSlot[];
};

const cache = new Map<number, Promise<FacilityLayout>>();

/** Sơ đồ public của cơ sở; cache theo phiên trang để nhiều thẻ cùng cơ sở chỉ gọi một lần. */
export function fetchFacilityLayout(facilityId: number, fresh = false): Promise<FacilityLayout> {
  const cached = cache.get(facilityId);
  if (cached && !fresh) return cached;
  const request = apiRequest<FacilityLayout>(`/api/facilities/${facilityId}/layout`);
  cache.set(facilityId, request);
  request.catch(() => cache.delete(facilityId));
  return request;
}

export function sideLabel(side: string | null | undefined): string {
  return side === "Left" ? "bên trái" : side === "Right" ? "bên phải" : "—";
}

/** Có đủ ba trường vị trí. */
export function isPlaced(unit: {
  floorNo?: number | null;
  aisleSide?: string | null;
  aislePosition?: number | null;
}): unit is UnitPlacement {
  return (
    unit.floorNo != null &&
    (unit.aisleSide === "Left" || unit.aisleSide === "Right") &&
    unit.aislePosition != null
  );
}

/** "Tầng 2 · bên trái lối đi · ô thứ 3 từ cửa vào". */
export function locationText(unit: UnitPlacement): string {
  return `Tầng ${unit.floorNo} · ${sideLabel(unit.aisleSide)} lối đi · ô thứ ${unit.aislePosition} từ cửa vào`;
}

/** Câu chỉ đường cho khách / FS. */
export function directionsText(unit: UnitPlacement): string {
  const floor = unit.floorNo === 1 ? "Ở tầng 1" : `Lên tầng ${unit.floorNo}`;
  return `${floor}, từ cửa vào đi thẳng theo lối đi; khoang nằm ${sideLabel(unit.aisleSide)} tay, ô thứ ${unit.aislePosition}.`;
}

/** Chữ cái gợi ý cho mã khoang theo tầng: tầng 1 → A, tầng 2 → B… */
export function floorLetter(floorNo: number): string {
  return String.fromCharCode(64 + Math.min(Math.max(floorNo, 1), 26));
}

/** Link chỉ đường Google Maps tới toạ độ. */
export function directionsUrl(latitude: number, longitude: number): string {
  return `https://www.google.com/maps/dir/?api=1&destination=${latitude},${longitude}`;
}
