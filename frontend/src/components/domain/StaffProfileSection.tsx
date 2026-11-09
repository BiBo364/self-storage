import { useEffect, useState } from "react";
import { errorMessage } from "../../app/http";
import {
  formatDmy,
  type StaffProfile,
  type StaffProfileInput,
  type StaffProfileSummary,
} from "../../app/services/hr";
import { Button } from "../ui/Button";
import { DemoNotice } from "../ui/DemoNotice";
import { INPUT_ELEMENT, INPUT_WRAP } from "../ui/FormField";
import { SectionTitle } from "../ui/SectionTitle";

type Field = keyof StaffProfileInput;

const FIELDS: { key: Field; label: string; type?: string; max: number }[] = [
  { key: "position", label: "Chức danh", max: 100 },
  { key: "hireDate", label: "Ngày vào làm", type: "date", max: 10 },
  { key: "idNumber", label: "Số CCCD / hộ chiếu", max: 20 },
  { key: "emergencyContactName", label: "Người liên hệ khẩn cấp", max: 100 },
  { key: "emergencyContactPhone", label: "SĐT liên hệ khẩn cấp", max: 20 },
  { key: "note", label: "Ghi chú", max: 1000 },
];

function toInput(profile: StaffProfile): Record<Field, string> {
  return {
    position: profile.position ?? "",
    hireDate: profile.hireDate ?? "",
    idNumber: profile.idNumber ?? "",
    emergencyContactName: profile.emergencyContactName ?? "",
    emergencyContactPhone: profile.emergencyContactPhone ?? "",
    note: profile.note ?? "",
  };
}

/** ADMIN / BOM sửa hồ sơ nhân viên (gồm số giấy tờ). Lưu là thay toàn bộ; ô trống = xoá. */
export function StaffProfileEditor({
  load,
  save,
}: {
  load: () => Promise<StaffProfile>;
  save: (input: StaffProfileInput) => Promise<StaffProfile>;
}) {
  const [values, setValues] = useState<Record<Field, string> | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null);
  const [loader] = useState(() => load);

  useEffect(() => {
    let active = true;
    loader().then(
      (profile) => active && setValues(toInput(profile)),
      (caught: unknown) => active && setLoadError(errorMessage(caught)),
    );
    return () => {
      active = false;
    };
  }, [loader]);

  async function submit() {
    if (!values) return;
    setBusy(true);
    setResult(null);
    try {
      const body = Object.fromEntries(
        FIELDS.map(({ key }) => [key, values[key].trim() === "" ? null : values[key].trim()]),
      ) as StaffProfileInput;
      setValues(toInput(await save(body)));
      setResult({ ok: true, message: "Đã lưu hồ sơ nhân viên." });
    } catch (caught) {
      setResult({ ok: false, message: errorMessage(caught) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="grid gap-3">
      <SectionTitle>Hồ sơ nhân viên</SectionTitle>
      {loadError ? (
        <DemoNotice tone="error">Không tải được hồ sơ: {loadError}</DemoNotice>
      ) : !values ? (
        <p className="m-0 text-label text-muted">Đang tải hồ sơ…</p>
      ) : (
        <>
          {FIELDS.map((field) => (
            <label key={field.key} className="grid gap-1.5 text-label text-muted">
              {field.label}
              <div className={INPUT_WRAP}>
                <input
                  className={INPUT_ELEMENT}
                  type={field.type ?? "text"}
                  maxLength={field.max}
                  value={values[field.key]}
                  onChange={(event) =>
                    setValues((current) =>
                      current ? { ...current, [field.key]: event.target.value } : current,
                    )
                  }
                />
              </div>
            </label>
          ))}
          <p className="m-0 text-label text-muted">
            Số giấy tờ chỉ ADMIN / BOM xem được; nhật ký thao tác chỉ ghi 4 ký tự cuối.
          </p>
          <div>
            <Button variant="secondary" pending={busy} onClick={submit}>
              Lưu hồ sơ
            </Button>
          </div>
          {result && (
            <DemoNotice tone={result.ok ? "success" : "error"}>{result.message}</DemoNotice>
          )}
        </>
      )}
    </section>
  );
}

/** Hồ sơ chỉ đọc cho FM / FS — kiểu dữ liệu không có số giấy tờ. */
export function StaffProfileDetails({ profile }: { profile: StaffProfileSummary }) {
  const rows: [string, string][] = [
    ["Chức danh", profile.position ?? "—"],
    ["Ngày vào làm", formatDmy(profile.hireDate)],
    ["Người liên hệ khẩn cấp", profile.emergencyContactName ?? "—"],
    ["SĐT liên hệ khẩn cấp", profile.emergencyContactPhone ?? "—"],
    ["Ghi chú", profile.note ?? "—"],
  ];
  return (
    <dl className="m-0 grid grid-cols-[minmax(0,180px)_minmax(0,1fr)] gap-x-4 gap-y-2 text-small">
      {rows.map(([label, value]) => (
        <div key={label} className="contents">
          <dt className="text-muted">{label}</dt>
          <dd className="m-0 text-ink">{value}</dd>
        </div>
      ))}
    </dl>
  );
}
