import { Calendar, Check, Clock3 } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { formatDate, formatTime } from "../../app/format";
import { errorMessage } from "../../app/http";
import {
  fetchRescheduleSlots,
  rescheduleStaffAppointment,
  type RescheduleWindow,
  type RescheduledAppointment,
} from "../../app/staffSchedule";
import { Button } from "../ui/Button";
import { DemoNotice } from "../ui/DemoNotice";
import { DetailPanel } from "../ui/DetailPanel";
import { SurfaceState } from "../ui/SurfaceState";

const WEEKDAY = ["CN", "T2", "T3", "T4", "T5", "T6", "T7"];

function weekday(isoDay: string): string {
  const [y, m, d] = isoDay.split("-").map(Number);
  return WEEKDAY[new Date(y, m - 1, d).getDay()];
}

const PICK_CLASS =
  "border-2 text-small font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50";
const PICK_ON = "border-brand bg-brand text-signal-ink";
const PICK_OFF = "border-rule-strong bg-surface text-muted hover:border-ink hover:text-ink";

/**
 * FS dời lịch nhận kho: chọn ngày (hôm nay → hạn giữ đơn), một trong 3 khung còn trống và lý do. Khách đã đến thì
 * phiên bàn giao hiện tại đóng lại (giữ làm lịch sử) và buổi mới làm lại từ bước xác minh.
 */
export function ReschedulePanel({
  appointmentId,
  summary,
  arrived,
  onClose,
  onDone,
}: {
  appointmentId: number;
  summary: string;
  arrived: boolean;
  onClose: () => void;
  onDone: (result: RescheduledAppointment) => void;
}) {
  const [slotWindow, setSlotWindow] = useState<RescheduleWindow | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [date, setDate] = useState<string | null>(null);
  const [startAt, setStartAt] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    fetchRescheduleSlots(appointmentId)
      .then((data) => {
        if (active) {
          setSlotWindow(data);
          setLoadError(null);
        }
      })
      .catch((caught) => {
        if (active) setLoadError(errorMessage(caught));
      });
    return () => {
      active = false;
    };
  }, [appointmentId, reloadKey]);

  const days = slotWindow?.days ?? [];
  const selectedDay = days.find((day) => day.date === date) ?? null;
  const hasOpenSlot = days.some((day) => day.slots.some((slot) => slot.available));

  async function submit() {
    if (!date || !startAt || !reason.trim()) return;
    setBusy(true);
    setSubmitError(null);
    try {
      onDone(await rescheduleStaffAppointment(appointmentId, date, startAt, reason.trim()));
    } catch (caught) {
      setSubmitError(errorMessage(caught));
      setStartAt(null);
      setReloadKey((key) => key + 1);
    } finally {
      setBusy(false);
    }
  }

  let body: ReactNode;
  if (loadError) {
    body = (
      <SurfaceState
        variant="error"
        title="Không tải được khung giờ"
        description={loadError}
        action={{ label: "Thử lại", onClick: () => setReloadKey((key) => key + 1) }}
      />
    );
  } else if (!slotWindow) {
    body = <SurfaceState variant="loading" title="Đang tải khung giờ…" />;
  } else if (!hasOpenSlot) {
    body = (
      <SurfaceState
        variant="empty"
        title="Không còn khung trống trước hạn giữ kho"
        description="Huỷ lịch để khách tự đặt lại, hoặc liên hệ FM."
      />
    );
  } else {
    body = (
      <div className="grid gap-5">
        <div>
          <span className="flex items-center gap-1.5 text-label text-muted">
            <Calendar size={14} /> Ngày mới ({formatDate(slotWindow.minDate)} –{" "}
            {formatDate(slotWindow.maxDate)})
          </span>
          <div className="mt-2.5 flex flex-wrap gap-2">
            {days.map((day) => {
              const open = day.slots.some((slot) => slot.available);
              const active = day.date === date;
              return (
                <button
                  key={day.date}
                  type="button"
                  disabled={!open}
                  aria-pressed={active}
                  className={`grid min-h-[52px] min-w-[72px] place-items-center px-3 ${PICK_CLASS} ${
                    active ? PICK_ON : PICK_OFF
                  }`}
                  onClick={() => {
                    setDate(day.date);
                    setStartAt(null);
                  }}
                >
                  <span className="text-label">{weekday(day.date)}</span>
                  {formatDate(day.date).slice(0, 5)}
                </button>
              );
            })}
          </div>
        </div>
        <div>
          <span className="flex items-center gap-1.5 text-label text-muted">
            <Clock3 size={14} /> Khung giờ
          </span>
          {selectedDay ? (
            <div className="mt-2.5 flex flex-wrap gap-2">
              {selectedDay.slots.map((slot) => (
                <button
                  key={slot.startAt}
                  type="button"
                  disabled={!slot.available}
                  aria-pressed={startAt === slot.startAt}
                  className={`min-h-[42px] px-4 ${PICK_CLASS} ${
                    startAt === slot.startAt ? PICK_ON : PICK_OFF
                  }`}
                  onClick={() => setStartAt(slot.startAt)}
                >
                  {formatTime(slot.startAt)} – {formatTime(slot.endAt)}
                  {!slot.available && " · Hết chỗ"}
                </button>
              ))}
            </div>
          ) : (
            <p className="mt-2 text-small text-muted">Chọn một ngày để xem khung giờ.</p>
          )}
        </div>
        <label className="grid gap-1.5 text-label text-muted">
          Lý do dời lịch
          <textarea
            className="min-h-[90px] rounded-lg border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink outline-0 transition-colors focus:border-brand"
            value={reason}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
            placeholder="Ví dụ: Khách quên CCCD, hẹn quay lại hôm khác."
          />
        </label>
      </div>
    );
  }

  return (
    <DetailPanel title="Dời lịch nhận kho" eyebrow={summary} onClose={onClose}>
      <p className="mb-4 mt-0 text-small text-muted">
        {arrived
          ? "Phiên bàn giao hiện tại sẽ đóng lại (thông tin giữ làm lịch sử, xem lại được ở buổi mới). Hợp đồng nháp hoặc đã ký chưa thanh toán bị huỷ; buổi mới làm lại xác minh, kiểm tra khoang và lập hợp đồng theo ngày mới."
          : "Lịch cũ được huỷ với lý do dời lịch, lịch mới vẫn do bạn phụ trách. Khách và FM nhận thông báo giờ mới."}
      </p>
      {body}
      {submitError && (
        <div className="mt-4">
          <DemoNotice tone="error">{submitError}</DemoNotice>
        </div>
      )}
      <div className="mt-5 flex flex-wrap gap-2">
        <Button pending={busy} disabled={!date || !startAt || !reason.trim()} onClick={submit}>
          <Check size={14} /> Dời lịch
        </Button>
        <Button variant="secondary" disabled={busy} onClick={onClose}>
          Đóng
        </Button>
      </div>
    </DetailPanel>
  );
}
