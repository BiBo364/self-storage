import { Building2, Plus } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { errorMessage, errorStatus } from "../../app/http";
import {
  assignFacilityManager,
  createBomFacility,
  fetchBomFacilities,
  fetchBomManagers,
  setFacilityAccessOptions,
  updateBomFacility,
  type FacilityRow,
  type ManagerRow,
} from "../../app/services/bom";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FIELD_LABEL } from "../../components/ui/FormField";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const INPUT =
  "min-h-[42px] w-full rounded-[2px] border border-rule-strong bg-surface px-3 text-small text-ink outline-0 focus:border-brand";

function TextInput({
  id,
  label,
  value,
  onChange,
  required,
  placeholder,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  placeholder?: string;
}) {
  return (
    <div>
      <label className={FIELD_LABEL} htmlFor={id}>
        {label}
        {required && <span className="text-danger"> *</span>}
      </label>
      <input
        id={id}
        className={INPUT}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  );
}

/** Mốc 30 phút: giờ mở 00:00–23:30, giờ đóng 00:30–24:00. */
const OPEN_TIMES = Array.from(
  { length: 48 },
  (_, i) => `${String(Math.floor(i / 2)).padStart(2, "0")}:${i % 2 ? "30" : "00"}`,
);
const CLOSE_TIMES = [...OPEN_TIMES.slice(1), "24:00"];
const HOURS_PRESETS: [string, string][] = [
  ["07:00", "21:00"],
  ["08:00", "20:00"],
  ["06:00", "22:00"],
  ["00:00", "24:00"],
];

function formatHours(open: string, close: string): string {
  return `${open}–${close}`;
}

/** Đọc "7:00-21:00", "08:00–20:00 hằng ngày"… thành cặp giờ; null nếu không đọc được. */
function parseHours(value: string): { open: string; close: string } | null {
  const match = /^\s*(\d{1,2}):(\d{2})\s*[–-]\s*(\d{1,2}):(\d{2})/.exec(value);
  if (!match) return null;
  const open = `${match[1].padStart(2, "0")}:${match[2]}`;
  const close = `${match[3].padStart(2, "0")}:${match[4]}`;
  if (!OPEN_TIMES.includes(open) || !CLOSE_TIMES.includes(close) || close <= open) return null;
  return { open, close };
}

function HoursPicker({
  id,
  label,
  value,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const parsed = parseHours(value);
  const open = parsed?.open ?? "";
  const close = parsed?.close ?? "";

  // Chọn một đầu khi đầu kia trống/không hợp lệ → tự điền đầu kia (mặc định 07:00 / 21:00).
  function pickOpen(next: string) {
    const fallback = "21:00" > next ? "21:00" : CLOSE_TIMES.find((time) => time > next)!;
    onChange(formatHours(next, close > next ? close : fallback));
  }

  function pickClose(next: string) {
    const earlier = OPEN_TIMES.filter((time) => time < next);
    const fallback = "07:00" < next ? "07:00" : earlier[earlier.length - 1];
    onChange(formatHours(open || fallback, next));
  }

  return (
    <div>
      <span className={FIELD_LABEL} id={`${id}-label`}>
        {label}
      </span>
      <div className="flex flex-wrap gap-2" role="group" aria-labelledby={`${id}-label`}>
        {HOURS_PRESETS.map(([presetOpen, presetClose]) => {
          const active = open === presetOpen && close === presetClose;
          return (
            <Button
              key={presetOpen + presetClose}
              size="sm"
              variant={active ? "primary" : "secondary"}
              aria-pressed={active}
              onClick={() => onChange(formatHours(presetOpen, presetClose))}
            >
              {presetOpen === "00:00" && presetClose === "24:00"
                ? "Cả ngày (24h)"
                : formatHours(presetOpen, presetClose)}
            </Button>
          );
        })}
      </div>
      <div className="mt-2 grid grid-cols-2 gap-2">
        <select
          id={`${id}-open`}
          aria-label="Giờ mở cửa"
          className={INPUT}
          value={open}
          onChange={(event) => pickOpen(event.target.value)}
        >
          {!open && <option value="">Mở cửa…</option>}
          {OPEN_TIMES.map((time) => (
            <option key={time} value={time}>
              Mở {time}
            </option>
          ))}
        </select>
        <select
          id={`${id}-close`}
          aria-label="Giờ đóng cửa"
          className={INPUT}
          value={close}
          onChange={(event) => pickClose(event.target.value)}
        >
          {!close && <option value="">Đóng cửa…</option>}
          {CLOSE_TIMES.filter((time) => !open || time > open).map((time) => (
            <option key={time} value={time}>
              Đóng {time}
            </option>
          ))}
        </select>
      </div>
      {value && !parsed && (
        <p className="mt-1.5 mb-0 text-label text-muted">
          Giá trị hiện tại: “{value}” — chọn giờ ở trên để thay.
        </p>
      )}
      {value && (
        <button
          type="button"
          className="mt-1.5 text-label text-muted underline hover:text-ink"
          onClick={() => onChange("")}
        >
          Bỏ trống giờ mở cửa
        </button>
      )}
    </div>
  );
}

function statusTag(status: string): ReactNode {
  return status === "Active" ? (
    <StatusTag tone="success">Đang hoạt động</StatusTag>
  ) : (
    <StatusTag tone="neutral">{status === "Inactive" ? "Ngừng hoạt động" : status}</StatusTag>
  );
}

/** BOM — cơ sở và FM phụ trách. */
export function BomFacilitiesPage({ navigate }: { navigate: Navigate }) {
  void navigate;
  const [facilities, setFacilities] = useState<FacilityRow[]>([]);
  const [managers, setManagers] = useState<ManagerRow[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const [facilityList, managerList] = await Promise.all([
          fetchBomFacilities(),
          fetchBomManagers(),
        ]);
        if (!active) return;
        setFacilities(facilityList);
        setManagers(managerList);
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [reloadKey]);

  function reload() {
    setIsLoading(true);
    setError(null);
    setReloadKey((key) => key + 1);
  }

  function upsert(row: FacilityRow) {
    setFacilities((list) => {
      // Mỗi FM một cơ sở — backend đã gỡ FM khỏi cơ sở cũ, gỡ theo ở danh sách hiện tại.
      const cleared =
        row.fmAccountId === null
          ? list
          : list.map((item) =>
              item.id !== row.id && item.fmAccountId === row.fmAccountId
                ? { ...item, fmAccountId: null, fmEmail: null }
                : item,
            );
      return cleared.some((item) => item.id === row.id)
        ? cleared.map((item) => (item.id === row.id ? row : item))
        : [...cleared, row];
    });
  }

  const openFacility = facilities.find((facility) => facility.id === openId) ?? null;

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Cơ sở & FM</h1>
          <p className="mt-1.5 text-small text-muted">
            Thông tin cơ sở, FM phụ trách và hình thức truy cập khoang.
          </p>
        </div>
        <Button
          onClick={() => {
            setNotice(null);
            setCreating(true);
          }}
        >
          <Plus size={16} /> Thêm cơ sở
        </Button>
      </div>

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải cơ sở…" />
      ) : error ? (
        error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền quản lý cơ sở"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được danh sách cơ sở"
            description={error.message}
            action={{ label: "Thử lại", onClick: reload }}
          />
        )
      ) : facilities.length === 0 ? (
        <SurfaceState
          variant="empty"
          title="Chưa có cơ sở nào"
          description="Thêm cơ sở đầu tiên để bắt đầu nhận yêu cầu thuê kho."
          action={{ label: "Thêm cơ sở", onClick: () => setCreating(true) }}
        />
      ) : (
        <div className="grid gap-2">
          {facilities.map((facility) => (
            <button
              key={facility.id}
              className="flex flex-wrap items-start justify-between gap-4 bg-surface p-4 text-left transition-colors hover:bg-surface-subtle"
              onClick={() => {
                setNotice(null);
                setOpenId(facility.id);
              }}
            >
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-[16px] font-medium text-ink">{facility.code}</span>
                  {statusTag(facility.status)}
                </div>
                <strong className="mt-1 flex items-center gap-2 text-section text-ink">
                  <Building2 size={16} /> {facility.name}
                </strong>
                <span className="mt-1 block text-small text-muted">
                  {facility.address ?? "—"}
                  {facility.operatingHours ? ` · ${facility.operatingHours}` : ""}
                </span>
                <span className="mt-1 block text-label text-muted">
                  FM: {facility.fmEmail ?? "Chưa gán"} · Truy cập:{" "}
                  {[facility.keyAccess && "khoá cơ", facility.codeAccess && "mã số"]
                    .filter(Boolean)
                    .join(", ") || "—"}
                </span>
              </div>
              <div className="text-right">
                <strong className="block text-stat tabular-nums text-ink">
                  {facility.rentedCount}/{facility.unitCount}
                </strong>
                <span className="text-label text-muted">khoang đang thuê</span>
              </div>
            </button>
          ))}
        </div>
      )}

      {creating && (
        <CreatePanel
          onClose={() => setCreating(false)}
          onCreated={(row) => {
            upsert(row);
            setCreating(false);
            setNotice(`Đã tạo cơ sở ${row.name}.`);
          }}
        />
      )}

      {openFacility && (
        <EditPanel
          key={openFacility.id}
          facility={openFacility}
          managers={managers}
          onClose={() => setOpenId(null)}
          onSaved={(row, message) => {
            upsert(row);
            setNotice(message);
          }}
        />
      )}
    </div>
  );
}

function CreatePanel({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: (row: FacilityRow) => void;
}) {
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [address, setAddress] = useState("");
  const [hours, setHours] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!code.trim() || !name.trim()) {
      setError("Vui lòng nhập mã và tên cơ sở.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      onCreated(
        await createBomFacility({
          code: code.trim(),
          name: name.trim(),
          address: address.trim() || undefined,
          operatingHours: hours.trim() || undefined,
        }),
      );
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(false);
    }
  }

  return (
    <DetailPanel
      title="Thêm cơ sở"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>
            Huỷ
          </Button>
          <Button pending={busy} onClick={submit}>
            Tạo cơ sở
          </Button>
        </>
      }
    >
      <div className="grid gap-4">
        <TextInput
          id="facility-code"
          label="Mã cơ sở"
          value={code}
          onChange={setCode}
          required
          placeholder="Ví dụ: Q7"
        />
        <TextInput id="facility-name" label="Tên cơ sở" value={name} onChange={setName} required />
        <TextInput id="facility-address" label="Địa chỉ" value={address} onChange={setAddress} />
        <HoursPicker id="facility-hours" label="Giờ mở cửa" value={hours} onChange={setHours} />
      </div>
      {error && <DemoNotice tone="error">{error}</DemoNotice>}
    </DetailPanel>
  );
}

function EditPanel({
  facility,
  managers,
  onClose,
  onSaved,
}: {
  facility: FacilityRow;
  managers: ManagerRow[];
  onClose: () => void;
  onSaved: (row: FacilityRow, message: string) => void;
}) {
  const [name, setName] = useState(facility.name);
  const [address, setAddress] = useState(facility.address ?? "");
  const [hours, setHours] = useState(facility.operatingHours ?? "");
  const [status, setStatus] = useState(facility.status);
  const [fmId, setFmId] = useState(facility.fmAccountId ? String(facility.fmAccountId) : "");
  const [keyAccess, setKeyAccess] = useState(facility.keyAccess);
  const [codeAccess, setCodeAccess] = useState(facility.codeAccess);
  const [busy, setBusy] = useState<"info" | "manager" | "access" | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function save(kind: "info" | "manager" | "access") {
    setBusy(kind);
    setError(null);
    try {
      if (kind === "info") {
        onSaved(
          await updateBomFacility(facility.id, {
            name: name.trim(),
            address: address.trim(),
            operatingHours: hours.trim(),
            status,
          }),
          "Đã cập nhật cơ sở.",
        );
      } else if (kind === "manager") {
        onSaved(
          await assignFacilityManager(facility.id, fmId ? Number(fmId) : null),
          fmId ? "Đã gán FM cho cơ sở." : "Đã bỏ FM khỏi cơ sở.",
        );
      } else {
        onSaved(
          await setFacilityAccessOptions(facility.id, keyAccess, codeAccess),
          "Đã cập nhật hình thức truy cập.",
        );
      }
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(null);
    }
  }

  const knownManager = managers.some((manager) => String(manager.id) === fmId);

  return (
    <DetailPanel title={facility.name} eyebrow={`Cơ sở ${facility.code}`} onClose={onClose}>
      {error && <DemoNotice tone="error">{error}</DemoNotice>}

      <div className="mt-4 grid gap-4">
        <SectionTitle>Thông tin</SectionTitle>
        <TextInput id="edit-name" label="Tên cơ sở" value={name} onChange={setName} required />
        <TextInput id="edit-address" label="Địa chỉ" value={address} onChange={setAddress} />
        <HoursPicker id="edit-hours" label="Giờ mở cửa" value={hours} onChange={setHours} />
        <div>
          <label className={FIELD_LABEL} htmlFor="edit-status">
            Trạng thái
          </label>
          <select
            id="edit-status"
            className={INPUT}
            value={status}
            onChange={(event) => setStatus(event.target.value)}
          >
            <option value="Active">Đang hoạt động</option>
            <option value="Inactive">Ngừng hoạt động</option>
          </select>
        </div>
        <Button
          className="w-fit"
          pending={busy === "info"}
          disabled={busy !== null || !name.trim()}
          onClick={() => save("info")}
        >
          Lưu thông tin
        </Button>
      </div>

      <div className="mt-8 grid gap-4">
        <SectionTitle>FM phụ trách</SectionTitle>
        <p className="m-0 text-small text-muted">
          Mỗi FM phụ trách một cơ sở; gán FM đang ở cơ sở khác sẽ gỡ FM khỏi cơ sở cũ.
        </p>
        <select
          aria-label="FM phụ trách"
          className={INPUT}
          value={fmId}
          onChange={(event) => setFmId(event.target.value)}
        >
          <option value="">Chưa gán FM</option>
          {!knownManager && fmId && (
            <option value={fmId}>{facility.fmEmail ?? `Tài khoản ${fmId}`}</option>
          )}
          {managers.map((manager) => (
            <option key={manager.id} value={manager.id}>
              {manager.name ? `${manager.name} · ${manager.email}` : manager.email}
            </option>
          ))}
        </select>
        <Button
          variant="secondary"
          className="w-fit"
          pending={busy === "manager"}
          disabled={busy !== null}
          onClick={() => save("manager")}
        >
          Lưu FM
        </Button>
      </div>

      <div className="mt-8 grid gap-3">
        <SectionTitle>Hình thức truy cập</SectionTitle>
        <label className="flex items-center gap-2.5 text-small text-ink">
          <input
            type="checkbox"
            className="h-4 w-4 accent-[var(--brand)]"
            checked={keyAccess}
            onChange={(event) => setKeyAccess(event.target.checked)}
          />
          Chìa khoá cơ
        </label>
        <label className="flex items-center gap-2.5 text-small text-ink">
          <input
            type="checkbox"
            className="h-4 w-4 accent-[var(--brand)]"
            checked={codeAccess}
            onChange={(event) => setCodeAccess(event.target.checked)}
          />
          Mã số
        </label>
        <Button
          variant="secondary"
          className="w-fit"
          pending={busy === "access"}
          disabled={busy !== null}
          onClick={() => save("access")}
        >
          Lưu hình thức truy cập
        </Button>
      </div>
    </DetailPanel>
  );
}
