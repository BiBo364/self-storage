import { useEffect, useRef, useState } from "react";
import { errorMessage, errorStatus } from "../../app/http";
import { formatDmy, type StaffReport } from "../../app/services/hr";
import { Button } from "../ui/Button";
import { INPUT_ELEMENT, INPUT_WRAP } from "../ui/FormField";
import { SurfaceState } from "../ui/SurfaceState";

/**
 * Báo cáo nhân sự (FM một cơ sở / BOM toàn chuỗi): bộ lọc ngày + bảng một dòng mỗi FS. Bỏ trống ngày = tháng hiện
 * tại (backend áp mặc định).
 */
export function StaffReportTable({
  load,
  filterKey = "",
  filters,
}: {
  load: (range: { from?: string; to?: string }) => Promise<StaffReport>;
  /** Đổi giá trị → tải lại (vd khi BOM đổi cơ sở). */
  filterKey?: string;
  filters?: React.ReactNode;
}) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [reloadKey, setReloadKey] = useState(0);
  const [report, setReport] = useState<StaffReport | null>(null);
  // Lần tải đã xong ứng với bộ lọc nào — khác bộ lọc hiện tại = đang tải.
  const [settled, setSettled] = useState<{
    token: string;
    error: { status: number; message: string } | null;
  } | null>(null);
  const loadRef = useRef(load);
  const token = `${from}|${to}|${filterKey}|${reloadKey}`;
  const isLoading = settled?.token !== token;
  const error = isLoading ? null : (settled?.error ?? null);

  useEffect(() => {
    loadRef.current = load;
  });

  useEffect(() => {
    let active = true;
    const [rangeFrom, rangeTo] = token.split("|");
    loadRef.current({ from: rangeFrom || undefined, to: rangeTo || undefined }).then(
      (result) => {
        if (!active) return;
        setReport(result);
        setSettled({ token, error: null });
      },
      (caught: unknown) => {
        if (!active) return;
        setSettled({
          token,
          error: { status: errorStatus(caught), message: errorMessage(caught) },
        });
      },
    );
    return () => {
      active = false;
    };
  }, [token]);

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-end gap-3">
        {filters}
        <DateInput label="Từ ngày" value={from} onChange={setFrom} />
        <DateInput label="Đến ngày" value={to} onChange={setTo} />
        {(from || to) && (
          <Button
            variant="secondary"
            onClick={() => {
              setFrom("");
              setTo("");
            }}
          >
            Tháng này
          </Button>
        )}
      </div>

      {isLoading && !report ? (
        <SurfaceState variant="loading" title="Đang tải báo cáo nhân sự…" />
      ) : error ? (
        error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xem báo cáo nhân sự"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được báo cáo nhân sự"
            description={error.message}
            action={{ label: "Thử lại", onClick: () => setReloadKey((key) => key + 1) }}
          />
        )
      ) : !report || report.rows.length === 0 ? (
        <SurfaceState variant="empty" title="Không có FS trong phạm vi báo cáo" />
      ) : (
        <div className={`grid gap-2 ${isLoading ? "opacity-60" : ""}`}>
          <p className="m-0 text-label text-muted">
            Từ {formatDmy(report.from)} đến {formatDmy(report.to)}
          </p>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[820px] border-collapse text-small">
              <thead>
                <tr className="text-left text-label text-muted">
                  <th className="p-2 font-semibold">Nhân viên</th>
                  <th className="p-2 text-right font-semibold">Check-in xong</th>
                  <th className="p-2 text-right font-semibold">Trả kho xong</th>
                  <th className="p-2 text-right font-semibold">Bàn giao hoàn tất</th>
                  <th className="p-2 text-right font-semibold">Trả kho hoàn tất</th>
                  <th className="p-2 text-right font-semibold">Hỗ trợ đã xử lý</th>
                  <th className="p-2 text-right font-semibold">Giờ ca</th>
                  <th className="p-2 text-right font-semibold">Ngày nghỉ</th>
                </tr>
              </thead>
              <tbody>
                {report.rows.map((row) => (
                  <tr key={row.staffId} className="bg-surface">
                    <td className="p-2">
                      <strong className="block text-ink">{row.name ?? row.email}</strong>
                      <span className="text-label text-muted">
                        {row.email}
                        {row.status !== "ACTIVE" ? " · đã khoá" : ""}
                      </span>
                    </td>
                    <td className="p-2 text-right text-ink">{row.completedCheckins}</td>
                    <td className="p-2 text-right text-ink">{row.completedReturns}</td>
                    <td className="p-2 text-right text-ink">{row.handoversCompleted}</td>
                    <td className="p-2 text-right text-ink">{row.checkoutsCompleted}</td>
                    <td className="p-2 text-right text-ink">{row.supportResolved}</td>
                    <td className="p-2 text-right text-ink">
                      {Number(row.shiftHours).toLocaleString("vi-VN")}
                    </td>
                    <td className="p-2 text-right text-ink">{row.leaveDays}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

function DateInput({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <label className="grid gap-1.5 text-label text-muted">
      {label}
      <div className={`${INPUT_WRAP} min-w-[180px]`}>
        <input
          type="date"
          className={INPUT_ELEMENT}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      </div>
    </label>
  );
}
