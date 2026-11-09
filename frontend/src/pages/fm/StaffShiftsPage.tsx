import { ChevronLeft, ChevronRight, Plus } from "lucide-react";
import { useEffect, useState } from "react";
import { fetchCurrentFmFacility, type FmFacility } from "../../app/fmFacility";
import { fetchFacilityStaff, type FmStaff } from "../../app/fmStaff";
import { errorMessage, errorStatus } from "../../app/http";
import {
  addDays,
  createShift,
  deleteShift,
  fetchFacilityShifts,
  formatDmy,
  isoDate,
  mondayOf,
  updateShift,
  weekdayLabel,
  type StaffShift,
} from "../../app/services/hr";
import { Button } from "../../components/ui/Button";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FIELD_LABEL, INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { SurfaceState } from "../../components/ui/SurfaceState";

type Editing =
  { mode: "create"; staffId: number; shiftDate: string } | { mode: "edit"; shift: StaffShift };

function staffName(member: { name: string | null; email: string }) {
  return member.name ?? member.email;
}

/** FM xếp ca cho FS của cơ sở theo tuần. Chồng giờ / nghỉ phép / lịch hẹn rơi khỏi ca do backend chặn (409). */
export function StaffShiftsPage() {
  const [weekStart, setWeekStart] = useState(() => mondayOf(isoDate(new Date())));
  const [facility, setFacility] = useState<FmFacility | null>(null);
  const [staff, setStaff] = useState<FmStaff[]>([]);
  const [shifts, setShifts] = useState<StaffShift[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const days = Array.from({ length: 7 }, (_, index) => addDays(weekStart, index));
  const weekEnd = days[6];

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const current = await fetchCurrentFmFacility();
        const [staffList, shiftList] = await Promise.all([
          fetchFacilityStaff(current.id),
          fetchFacilityShifts(current.id, weekStart, weekEnd),
        ]);
        if (!active) return;
        setFacility(current);
        setStaff(staffList);
        setShifts(shiftList);
        setError(null);
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [weekStart, weekEnd, reloadKey]);

  function moveWeek(offset: number) {
    setIsLoading(true);
    setNotice(null);
    setWeekStart((current) => addDays(current, offset * 7));
  }

  function reload() {
    setIsLoading(true);
    setReloadKey((key) => key + 1);
  }

  // FS có ca trong tuần nhưng đã rời cơ sở vẫn hiện để FM xoá ca cũ.
  const rows: { id: number; name: string; email: string }[] = staff.map((member) => ({
    id: member.id,
    name: staffName(member),
    email: member.email,
  }));
  shifts.forEach((shift) => {
    if (!rows.some((row) => row.id === shift.staffId)) {
      rows.push({
        id: shift.staffId,
        name: `${staffName({ name: shift.staffName, email: shift.staffEmail })} (đã rời cơ sở)`,
        email: shift.staffEmail,
      });
    }
  });

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Ca làm việc</h1>
        <p className="mt-1.5 text-small text-muted">
          Xếp ca cho FS của {facility?.name ?? "cơ sở"}. Khi bắt buộc ca đang bật, FM chỉ phân công
          được FS có ca bao trọn giờ hẹn.
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

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải ca làm việc…" />
      ) : error ? (
        error.status === 403 || error.status === 404 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xếp ca cho cơ sở này"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được ca làm việc"
            description={error.message}
            action={{ label: "Thử lại", onClick: reload }}
          />
        )
      ) : rows.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Cơ sở chưa có FS"
          description="BOM gán FS vào cơ sở trước, sau đó FM xếp ca."
        />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[860px] border-collapse text-small">
            <thead>
              <tr className="text-left text-label text-muted">
                <th className="p-2 font-semibold">Nhân viên</th>
                {days.map((day) => (
                  <th key={day} className="p-2 font-semibold">
                    {weekdayLabel(day)} {formatDmy(day).slice(0, 5)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const assigned = staff.some((member) => member.id === row.id);
                return (
                  <tr key={row.id} className="bg-surface align-top">
                    <td className="p-2">
                      <strong className="block text-ink">{row.name}</strong>
                      <span className="text-label text-muted">{row.email}</span>
                    </td>
                    {days.map((day) => {
                      const cell = shifts.filter(
                        (shift) => shift.staffId === row.id && shift.shiftDate === day,
                      );
                      return (
                        <td key={day} className="p-2">
                          <div className="grid gap-1">
                            {cell.map((shift) => (
                              <button
                                key={shift.id}
                                className="bg-brand-soft px-2 py-1 text-left text-label font-semibold text-ink hover:bg-surface-subtle"
                                title={shift.note ?? undefined}
                                onClick={() => setEditing({ mode: "edit", shift })}
                              >
                                {shift.startTime}–{shift.endTime}
                                {shift.facilityId !== facility?.id && ` · ${shift.facilityCode}`}
                              </button>
                            ))}
                            {assigned && (
                              <button
                                className="inline-flex items-center gap-1 px-1 py-0.5 text-label text-muted hover:text-ink"
                                aria-label={`Thêm ca ${row.name} ngày ${formatDmy(day)}`}
                                onClick={() =>
                                  setEditing({ mode: "create", staffId: row.id, shiftDate: day })
                                }
                              >
                                <Plus size={12} /> Ca
                              </button>
                            )}
                          </div>
                        </td>
                      );
                    })}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {editing && facility && (
        <ShiftPanel
          key={
            editing.mode === "edit"
              ? `e${editing.shift.id}`
              : `c${editing.staffId}${editing.shiftDate}`
          }
          editing={editing}
          facilityId={facility.id}
          staffLabel={
            rows.find(
              (row) =>
                row.id === (editing.mode === "edit" ? editing.shift.staffId : editing.staffId),
            )?.name ?? ""
          }
          onClose={() => setEditing(null)}
          onSaved={(message) => {
            setEditing(null);
            setNotice(message);
            reload();
          }}
        />
      )}
    </div>
  );
}

function ShiftPanel({
  editing,
  facilityId,
  staffLabel,
  onClose,
  onSaved,
}: {
  editing: Editing;
  facilityId: number;
  staffLabel: string;
  onClose: () => void;
  onSaved: (message: string) => void;
}) {
  const initial =
    editing.mode === "edit"
      ? editing.shift
      : { shiftDate: editing.shiftDate, startTime: "07:00", endTime: "21:00", note: "" };
  const [shiftDate, setShiftDate] = useState(initial.shiftDate);
  const [startTime, setStartTime] = useState(initial.startTime);
  const [endTime, setEndTime] = useState(initial.endTime);
  const [note, setNote] = useState(initial.note ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const otherFacility = editing.mode === "edit" && editing.shift.facilityId !== facilityId;

  async function save() {
    setBusy(true);
    setError(null);
    try {
      const body = { shiftDate, startTime, endTime, note: note.trim() || null };
      if (editing.mode === "edit") {
        await updateShift(editing.shift.id, body);
        onSaved(`Đã cập nhật ca ${startTime}–${endTime} ngày ${formatDmy(shiftDate)}.`);
      } else {
        await createShift({ ...body, facilityId, staffId: editing.staffId });
        onSaved(`Đã thêm ca ${startTime}–${endTime} ngày ${formatDmy(shiftDate)}.`);
      }
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (editing.mode !== "edit") return;
    setBusy(true);
    setError(null);
    try {
      await deleteShift(editing.shift.id);
      onSaved("Đã xoá ca làm việc.");
    } catch (caught) {
      setConfirmDelete(false);
      setError(errorMessage(caught));
    } finally {
      setBusy(false);
    }
  }

  return (
    <DetailPanel
      title={editing.mode === "edit" ? "Sửa ca làm việc" : "Thêm ca làm việc"}
      eyebrow={staffLabel}
      onClose={onClose}
      footer={
        otherFacility ? undefined : (
          <>
            {editing.mode === "edit" && (
              <Button variant="danger" disabled={busy} onClick={() => setConfirmDelete(true)}>
                Xoá ca
              </Button>
            )}
            <Button pending={busy} onClick={save}>
              Lưu ca
            </Button>
          </>
        )
      }
    >
      {otherFacility ? (
        <DemoNotice tone="info">
          Ca này thuộc cơ sở {editing.mode === "edit" ? editing.shift.facilityCode : ""} — FM của cơ
          sở đó quản lý.
        </DemoNotice>
      ) : (
        <div className="grid gap-3">
          <label className={FIELD_LABEL} htmlFor="shift-date">
            Ngày
          </label>
          <div className={INPUT_WRAP}>
            <input
              id="shift-date"
              type="date"
              className={INPUT_ELEMENT}
              value={shiftDate}
              onChange={(event) => setShiftDate(event.target.value)}
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <label className="grid gap-1.5 text-label text-muted">
              Bắt đầu
              <div className={INPUT_WRAP}>
                <input
                  type="time"
                  className={INPUT_ELEMENT}
                  value={startTime}
                  onChange={(event) => setStartTime(event.target.value)}
                />
              </div>
            </label>
            <label className="grid gap-1.5 text-label text-muted">
              Kết thúc
              <div className={INPUT_WRAP}>
                <input
                  type="time"
                  className={INPUT_ELEMENT}
                  value={endTime}
                  onChange={(event) => setEndTime(event.target.value)}
                />
              </div>
            </label>
          </div>
          <label className="grid gap-1.5 text-label text-muted">
            Ghi chú
            <div className={INPUT_WRAP}>
              <input
                className={INPUT_ELEMENT}
                maxLength={500}
                value={note}
                onChange={(event) => setNote(event.target.value)}
              />
            </div>
          </label>
          <p className="m-0 text-label text-muted">
            FS nhận thông báo khi ca ở tương lai thay đổi. Không xoá / thu hẹp được ca đang bao lịch
            hẹn đã giao cho FS.
          </p>
        </div>
      )}
      {error && <DemoNotice tone="error">{error}</DemoNotice>}
      {confirmDelete && (
        <ConfirmDialog
          title="Xoá ca làm việc?"
          description={`Ca ${initial.startTime}–${initial.endTime} ngày ${formatDmy(initial.shiftDate)}.`}
          confirmLabel="Xoá ca"
          isPending={busy}
          onConfirm={remove}
          onCancel={() => setConfirmDelete(false)}
        />
      )}
    </DetailPanel>
  );
}
