import { useEffect, useState } from "react";
import { errorMessage } from "../../app/http";
import { fetchBomFacilities, type FacilityRow } from "../../app/services/bom";
import { fetchBomStaffReport } from "../../app/services/hr";
import { StaffReportTable } from "../../components/domain/StaffReportTable";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { FilterSelect } from "../../components/ui/FilterSelect";

/** BOM: báo cáo nhân sự toàn chuỗi hoặc một cơ sở, kèm headcount từng cơ sở. */
export function BomStaffReportPage() {
  const [facilities, setFacilities] = useState<FacilityRow[]>([]);
  const [facilityId, setFacilityId] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    fetchBomFacilities().then(
      (list) => active && setFacilities(list),
      (caught: unknown) => active && setError(errorMessage(caught)),
    );
    return () => {
      active = false;
    };
  }, []);

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Báo cáo nhân sự</h1>
        <p className="mt-1.5 text-small text-muted">
          Việc FS đã hoàn tất, giờ ca và ngày nghỉ phép — toàn chuỗi hoặc từng cơ sở.
        </p>
      </div>
      {error && <DemoNotice tone="error">Không tải được danh sách cơ sở: {error}</DemoNotice>}
      {facilities.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {facilities.map((facility) => (
            <span key={facility.id} className="bg-surface px-3 py-2 text-label text-muted">
              <strong className="text-ink">{facility.code}</strong> · {facility.staffCount} FS
            </span>
          ))}
        </div>
      )}
      <StaffReportTable
        filterKey={facilityId}
        load={(range) =>
          fetchBomStaffReport({ facilityId: facilityId ? Number(facilityId) : null, ...range })
        }
        filters={
          <div className="min-w-[220px]">
            <FilterSelect label="Cơ sở" value={facilityId} onChange={setFacilityId}>
              <option value="">Toàn chuỗi</option>
              {facilities.map((facility) => (
                <option key={facility.id} value={facility.id}>
                  {facility.code} — {facility.name}
                </option>
              ))}
            </FilterSelect>
          </div>
        }
      />
    </div>
  );
}
