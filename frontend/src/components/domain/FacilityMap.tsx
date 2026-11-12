import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { useEffect, useRef } from "react";
import { directionsUrl } from "../../app/facilityLayout";

export type MapMarker = {
  id: number | string;
  latitude: number;
  longitude: number;
  title: string;
  subtitle?: string | null;
};

export type LatLng = { latitude: number; longitude: number };

/** Trung tâm TP.HCM — dùng khi chưa có ghim nào. */
const DEFAULT_CENTER: L.LatLngTuple = [10.7769, 106.7009];

const TILE_URL = "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png";
const ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>';

function pinIcon(picked = false) {
  return L.divIcon({
    className: "facility-pin",
    html: `<span class="facility-pin__dot${picked ? " is-picked" : ""}"></span>`,
    iconSize: [32, 32],
    iconAnchor: [16, 30],
    popupAnchor: [0, -28],
  });
}

function escapeHtml(value: string): string {
  return value.replace(
    /[&<>"']/g,
    (char) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char] ?? char,
  );
}

function round6(value: number): number {
  return Math.round(value * 1e6) / 1e6;
}

/**
 * Bản đồ OpenStreetMap (Leaflet). Hai chế độ:
 * - xem: ghim mọi cơ sở trong `markers`, bấm ghim mở popup có nút "Chỉ đường" (Google Maps);
 * - chọn vị trí: truyền `onPick` — bấm lên bản đồ để đặt / dời ghim `picked`.
 */
export function FacilityMap({
  markers = [],
  picked,
  onPick,
  height = 340,
  zoom = 13,
  onMarkerClick,
}: {
  markers?: MapMarker[];
  picked?: LatLng | null;
  onPick?: (value: LatLng) => void;
  height?: number;
  zoom?: number;
  onMarkerClick?: (id: MapMarker["id"]) => void;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const layerRef = useRef<L.LayerGroup | null>(null);
  const pickedRef = useRef<L.Marker | null>(null);
  const pickRef = useRef(onPick);
  const clickRef = useRef(onMarkerClick);
  useEffect(() => {
    pickRef.current = onPick;
    clickRef.current = onMarkerClick;
  });

  // Tạo bản đồ một lần; bản đồ sống cùng component.
  useEffect(() => {
    const element = containerRef.current;
    if (!element) return;
    const start = picked ? ([picked.latitude, picked.longitude] as L.LatLngTuple) : DEFAULT_CENTER;
    const map = L.map(element, { scrollWheelZoom: false, zoomControl: true }).setView(start, zoom);
    L.tileLayer(TILE_URL, { attribution: ATTRIBUTION, maxZoom: 19 }).addTo(map);
    layerRef.current = L.layerGroup().addTo(map);
    map.on("click", (event: L.LeafletMouseEvent) => {
      pickRef.current?.({
        latitude: round6(event.latlng.lat),
        longitude: round6(event.latlng.lng),
      });
    });
    // Panel trượt vào làm khung đổi kích thước sau khi bản đồ đã tính — đo lại một nhịp sau.
    const resize = window.setTimeout(() => map.invalidateSize(), 320);
    mapRef.current = map;
    return () => {
      window.clearTimeout(resize);
      map.remove();
      // Ghim cũ thuộc bản đồ vừa huỷ — bản đồ tạo lại (StrictMode / remount) phải gắn ghim mới.
      mapRef.current = null;
      layerRef.current = null;
      pickedRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Ghim các cơ sở; đổi danh sách thì canh khung cho thấy hết.
  const markerKey = markers.map((m) => `${m.id}:${m.latitude}:${m.longitude}:${m.title}`).join("|");
  useEffect(() => {
    const map = mapRef.current;
    const layer = layerRef.current;
    if (!map || !layer) return;
    layer.clearLayers();
    for (const marker of markers) {
      const popup = `<strong>${escapeHtml(marker.title)}</strong>${
        marker.subtitle ? `<br/><span>${escapeHtml(marker.subtitle)}</span>` : ""
      }<br/><a href="${directionsUrl(marker.latitude, marker.longitude)}" target="_blank" rel="noreferrer">Chỉ đường →</a>`;
      L.marker([marker.latitude, marker.longitude], { icon: pinIcon(), title: marker.title })
        .bindPopup(popup)
        .on("click", () => clickRef.current?.(marker.id))
        .addTo(layer);
    }
    if (markers.length === 1) {
      map.setView([markers[0].latitude, markers[0].longitude], Math.max(zoom, 15));
    } else if (markers.length > 1) {
      map.fitBounds(
        L.latLngBounds(markers.map((m) => [m.latitude, m.longitude] as L.LatLngTuple)),
        { padding: [36, 36], maxZoom: 15 },
      );
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [markerKey]);

  // Ghim đang chọn (form tạo / sửa cơ sở) — chỉ dời ghim, không giật khung.
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (!picked) {
      pickedRef.current?.remove();
      pickedRef.current = null;
      return;
    }
    const position: L.LatLngTuple = [picked.latitude, picked.longitude];
    if (pickedRef.current) {
      pickedRef.current.setLatLng(position);
    } else {
      pickedRef.current = L.marker(position, { icon: pinIcon(true), interactive: false }).addTo(
        map,
      );
      if (!map.getBounds().contains(position)) map.setView(position, Math.max(map.getZoom(), 15));
    }
  }, [picked]);

  return (
    <div
      ref={containerRef}
      className={`facility-map isolate w-full border border-border bg-surface-subtle ${onPick ? "cursor-crosshair" : ""}`}
      style={{ height }}
      role="region"
      aria-label={onPick ? "Bản đồ — bấm để đặt vị trí cơ sở" : "Bản đồ các cơ sở"}
    />
  );
}
