import { useEffect } from "react";
import { ArrowRight, X } from "lucide-react";
import { formatMoney } from "../../app/format";
import {
  formatArea,
  formatDimensions,
  type CatalogFacility,
  type UnitTypeOption,
} from "../../app/services/customer";
import { Navigate } from "../../app/types";
import { Button } from "../ui/Button";

export function UnitDetailsDialog({
  unit,
  facilities,
  facilityId,
  onClose,
  navigate,
}: {
  unit: UnitTypeOption;
  /** Cơ sở đang cung cấp loại khoang này (theo `unitTypeIds` của catalog). */
  facilities: CatalogFacility[];
  /** Cơ sở người dùng đang lọc — điền sẵn vào form yêu cầu. */
  facilityId?: number | null;
  onClose: () => void;
  navigate: Navigate;
}) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  function request() {
    const params = new URLSearchParams({ type: String(unit.id) });
    const facility = facilityId ?? (facilities.length === 1 ? facilities[0].id : null);
    if (facility) params.set("facility", String(facility));
    onClose();
    navigate(`/rental-requests/new?${params.toString()}`);
  }

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-[rgba(16,20,19,0.6)] p-5"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        className="relative w-[min(560px,100%)] border border-[var(--container-border)] bg-surface p-6 shadow-[var(--shadow-soft)]"
        role="dialog"
        aria-modal="true"
        aria-labelledby="unit-dialog-title"
      >
        <button
          className="absolute right-4 top-4 grid h-9 w-9 place-items-center border-0 bg-transparent text-muted hover:text-ink"
          onClick={onClose}
          aria-label="Đóng"
        >
          <X size={19} />
        </button>
        <span className="text-label text-muted">Loại khoang</span>
        <h2 id="unit-dialog-title" className="mt-1 pr-10 text-section text-ink">
          {unit.name}
        </h2>

        <div className="mt-5 flex items-stretch gap-5 max-[560px]:block">
          <div className="grid h-24 w-24 shrink-0 place-items-center border-2 border-[#101413] bg-brand text-stat font-semibold text-signal-ink max-[560px]:mb-4 max-[560px]:h-16 max-[560px]:w-16">
            {formatArea(unit.area)}
          </div>
          <div className="grid flex-1 grid-cols-2 gap-3 max-[560px]:grid-cols-1">
            <div>
              <span className="text-label text-muted">Diện tích</span>
              <strong className="block text-small font-semibold text-ink">
                {formatArea(unit.area)}
              </strong>
            </div>
            <div>
              <span className="text-label text-muted">Rộng × sâu × cao</span>
              <strong className="block text-small font-semibold text-ink">
                {formatDimensions(unit)}
              </strong>
            </div>
            <div className="col-span-2 max-[560px]:col-span-1">
              <span className="text-label text-muted">Giá niêm yết</span>
              <strong className="block text-stat text-ink">
                {formatMoney(unit.monthlyPrice)}
                <span className="ml-1 text-label font-normal text-muted">/ tháng</span>
              </strong>
            </div>
          </div>
        </div>

        <div className="mt-5">
          <span className="text-label text-muted">Có tại</span>
          {facilities.length === 0 ? (
            <p className="mt-1 text-small text-muted">
              Chưa có cơ sở nào cung cấp loại khoang này.
            </p>
          ) : (
            <ul className="mt-1.5 grid gap-1.5 pl-0">
              {facilities.map((facility) => (
                <li key={facility.id} className="list-none bg-surface-subtle px-3 py-2">
                  <strong className="block text-small font-semibold text-ink">
                    {facility.name}
                  </strong>
                  <span className="text-label text-muted">
                    {[facility.address, facility.operatingHours].filter(Boolean).join(" · ")}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <p className="mt-4 text-label text-muted">
          Giá tham khảo theo loại khoang; giá thuê chính thức nằm trong đề xuất khoang cơ sở gửi cho
          bạn.
        </p>

        <Button className="mt-5 w-full" onClick={request}>
          Gửi yêu cầu loại khoang này <ArrowRight size={16} />
        </Button>
      </div>
    </div>
  );
}
