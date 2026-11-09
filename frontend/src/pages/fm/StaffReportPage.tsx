import { useEffect, useState } from "react";
import { fetchCurrentFmFacility, type FmFacility } from "../../app/fmFacility";
import { errorMessage } from "../../app/http";
import { fetchFmStaffReport } from "../../app/services/hr";
import { StaffReportTable } from "../../components/domain/StaffReportTable";
import { SurfaceState } from "../../components/ui/SurfaceState";

/** Báo cáo nhân sự của cơ sở FM phụ trách. */
export function StaffReportPage() {
  const [facility, setFacility] = useState<FmFacility | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    fetchCurrentFmFacility().then(
      (current) => active && setFacility(current),
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
          Việc FS đã hoàn tất, giờ ca đã xếp và ngày nghỉ phép tại{" "}
          {facility?.name ?? "cơ sở của bạn"}. Mặc định tháng hiện tại.
        </p>
      </div>
      {error ? (
        <SurfaceState variant="error" title="Không tải được cơ sở" description={error} />
      ) : !facility ? (
        <SurfaceState variant="loading" title="Đang tải cơ sở…" />
      ) : (
        <StaffReportTable
          filterKey={String(facility.id)}
          load={(range) => fetchFmStaffReport({ facilityId: facility.id, ...range })}
        />
      )}
    </div>
  );
}
