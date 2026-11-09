import { ChevronLeft, ChevronRight } from "lucide-react";
import { useEffect, useState } from "react";
import { errorMessage, errorStatus } from "../../app/http";
import {
  addDays,
  fetchMyShifts,
  formatDmy,
  isoDate,
  mondayOf,
  weekdayLabel,
  type StaffShift,
} from "../../app/services/hr";
import { Button } from "../../components/ui/Button";
import { SurfaceState } from "../../components/ui/SurfaceState";

/** FS xem ca làm việc của mình theo tuần (mọi cơ sở). */
export function MyShiftsPage() {
  const [weekStart, setWeekStart] = useState(() => mondayOf(isoDate(new Date())));
  const [shifts, setShifts] = useState<StaffShift[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const days = Array.from({ length: 7 }, (_, index) => addDays(weekStart, index));
  const weekEnd = days[6];
  const today = isoDate(new Date());

  useEffect(() => {
    let active = true;
    fetchMyShifts(weekStart, weekEnd).then(
      (list) => {
        if (!active) return;
        setShifts(list);
        setError(null);
        setIsLoading(false);
      },
      (caught: unknown) => {
        if (!active) return;
        setError({ status: errorStatus(caught), message: errorMessage(caught) });
        setIsLoading(false);
      },
    );
    return () => {
      active = false;
    };
  }, [weekStart, weekEnd, reloadKey]);

  function moveWeek(offset: number) {
    setIsLoading(true);
    setWeekStart((current) => addDays(current, offset * 7));
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Ca của tôi</h1>
        <p className="mt-1.5 text-small text-muted">
          Ca FM đã xếp cho bạn. FM chỉ phân công lịch hẹn nằm trong ca của bạn tại đúng cơ sở.
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="secondary" size="sm" onClick={() => moveWeek(-1)}>
          <ChevronLeft size={14} /> Tuần trước
        </Button>
        <strong className="text-small text-ink">
          {formatDmy(weekStart)} – {formatDmy(weekEnd)}
        </strong>
        <Button variant="secondary" size="sm" onClick={() => moveWeek(1)}>
          Tuần sau <ChevronRight size={14} />
        </Button>
      </div>
      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải ca làm việc…" />
      ) : error ? (
        <SurfaceState
          variant={error.status === 403 ? "forbidden" : "error"}
          title="Không tải được ca làm việc"
          description={error.message}
          action={{
            label: "Thử lại",
            onClick: () => {
              setIsLoading(true);
              setReloadKey((key) => key + 1);
            },
          }}
        />
      ) : shifts.length === 0 ? (
        <SurfaceState variant="empty" title="Tuần này bạn chưa có ca" />
      ) : (
        <div className="grid gap-2">
          {days.map((day) => {
            const dayShifts = shifts.filter((shift) => shift.shiftDate === day);
            return (
              <div
                key={day}
                className={`flex flex-wrap items-center gap-4 bg-surface p-3 ${day === today ? "border-l-4 border-brand" : ""}`}
              >
                <strong className="w-[110px] text-small text-ink">
                  {weekdayLabel(day)} {formatDmy(day)}
                </strong>
                {dayShifts.length === 0 ? (
                  <span className="text-label text-muted">Không có ca</span>
                ) : (
                  dayShifts.map((shift) => (
                    <span key={shift.id} className="bg-brand-soft px-2 py-1 text-label text-ink">
                      {shift.startTime}–{shift.endTime} · {shift.facilityCode}
                      {shift.note ? ` · ${shift.note}` : ""}
                    </span>
                  ))
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
