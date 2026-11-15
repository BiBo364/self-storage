import { Send } from "lucide-react";
import { useEffect, useState } from "react";
import { errorMessage } from "../../app/http";
import {
  ACCOUNT_REQUEST_ROLE_LABEL,
  cancelAccountRequest,
  fetchAccountRequests,
  fetchBomFacilities,
  fetchBomManagers,
  fetchBomStaff,
  submitAccountRequest,
  type AccountRequestRole,
  type AccountRequestRow,
  type FacilityRow,
  type ManagerRow,
  type StaffRow,
} from "../../app/services/bom";
import { authGateway } from "../../app/auth";
import { AccountRequestCard } from "../../components/domain/AccountRequestCard";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { FIELD_LABEL } from "../../components/ui/FormField";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { SurfaceState } from "../../components/ui/SurfaceState";

const INPUT =
  "min-h-[42px] w-full rounded-lg border border-rule-strong bg-surface px-3 text-small text-ink outline-0 focus:border-brand";

const ROLES: AccountRequestRole[] = ["FM", "FS"];

type Mode = "new" | "replace";

/** Người có thể được thay: FM đang phụ trách một cơ sở, FS đang hoạt động và có cơ sở. */
type Candidate = { id: number; label: string; facilityCodes: string[] };

/** BOM — gửi yêu cầu tạo tài khoản FM / FS (tuyển thêm hoặc thay người nghỉ việc) cho ADMIN và theo dõi kết quả. */
export function BomAccountRequestsPage() {
  const [requests, setRequests] = useState<AccountRequestRow[]>([]);
  const [facilities, setFacilities] = useState<FacilityRow[]>([]);
  const [managers, setManagers] = useState<ManagerRow[]>([]);
  const [staff, setStaff] = useState<StaffRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [mode, setMode] = useState<Mode>("new");
  const [role, setRole] = useState<AccountRequestRole>("FS");
  const [replacesId, setReplacesId] = useState<number | null>(null);
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [note, setNote] = useState("");
  const [facilityIds, setFacilityIds] = useState<number[]>([]);
  const [busy, setBusy] = useState<number | "submit" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const myId = authGateway.getSession()?.user.id ?? null;

  useEffect(() => {
    let active = true;
    Promise.all([fetchAccountRequests(), fetchBomFacilities(), fetchBomManagers(), fetchBomStaff()])
      .then(([list, facilityList, managerList, staffList]) => {
        if (!active) return;
        setRequests(list);
        setFacilities(facilityList);
        setManagers(managerList);
        setStaff(staffList);
        setLoadError(null);
      })
      .catch((caught) => active && setLoadError(errorMessage(caught)))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [reloadKey]);

  const facilityCode = (id: number) => facilities.find((item) => item.id === id)?.code ?? `#${id}`;
  const pendingReplacements = new Set(
    requests
      .filter((request) => request.status === "Pending" && request.replacesAccountId !== null)
      .map((request) => request.replacesAccountId),
  );
  const candidates: Candidate[] = (
    role === "FM"
      ? facilities
          .filter((facility) => facility.fmAccountId !== null)
          .map((facility) => {
            const manager = managers.find((item) => item.id === facility.fmAccountId);
            return {
              id: facility.fmAccountId as number,
              label: `${manager?.name ?? facility.fmEmail ?? "FM"} (${facility.fmEmail ?? ""}) — FM ${facility.code}`,
              facilityCodes: [facility.code],
            };
          })
      : staff
          .filter((member) => member.status === "ACTIVE" && member.facilityIds.length > 0)
          .map((member) => ({
            id: member.id,
            label: `${member.name ?? member.email} (${member.email})`,
            facilityCodes: member.facilityIds.map(facilityCode),
          }))
  ).filter((candidate) => !pendingReplacements.has(candidate.id));
  const replaced = candidates.find((candidate) => candidate.id === replacesId) ?? null;

  function changeRole(next: AccountRequestRole) {
    setRole(next);
    setFacilityIds([]);
    setReplacesId(null);
  }

  function changeMode(next: Mode) {
    setMode(next);
    setFacilityIds([]);
    setReplacesId(null);
  }

  function toggleFacility(id: number) {
    if (role === "FM") {
      setFacilityIds([id]);
      return;
    }
    setFacilityIds((list) =>
      list.includes(id) ? list.filter((item) => item !== id) : [...list, id],
    );
  }

  async function submit() {
    setError(null);
    setNotice(null);
    if (!email.trim() || !name.trim()) {
      setError("Vui lòng nhập email và họ tên.");
      return;
    }
    if (mode === "replace" && replacesId === null) {
      setError(`Chọn ${role} nghỉ việc cần thay.`);
      return;
    }
    if (mode === "new" && role === "FM" && facilityIds.length !== 1) {
      setError("Chọn một cơ sở đang trống FM.");
      return;
    }
    if (mode === "new" && role === "FS" && facilityIds.length === 0) {
      setError("Chọn ít nhất một cơ sở cho FS.");
      return;
    }
    setBusy("submit");
    try {
      const created = await submitAccountRequest({
        role,
        email: email.trim(),
        name: name.trim(),
        phone: phone.trim() || undefined,
        facilityIds: mode === "new" ? facilityIds : undefined,
        note: note.trim() || undefined,
        replacesAccountId: mode === "replace" ? (replacesId ?? undefined) : undefined,
      });
      setRequests((list) => [created, ...list]);
      setEmail("");
      setName("");
      setPhone("");
      setNote("");
      setFacilityIds([]);
      setReplacesId(null);
      setNotice(
        created.replacesAccountId !== null
          ? `Đã gửi yêu cầu tạo tài khoản cho ${created.name} thay ${created.replacesName ?? created.replacesEmail}. Admin duyệt xong, việc cũ chuyển sang người mới.`
          : `Đã gửi yêu cầu tạo tài khoản cho ${created.name}. Admin sẽ duyệt và đặt mật khẩu.`,
      );
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(null);
    }
  }

  async function cancel(id: number) {
    setBusy(id);
    setError(null);
    try {
      const updated = await cancelAccountRequest(id);
      setRequests((list) => list.map((item) => (item.id === id ? updated : item)));
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(null);
    }
  }

  const pending = requests.filter((request) => request.status === "Pending");
  const done = requests.filter((request) => request.status !== "Pending");

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Yêu cầu tạo tài khoản</h1>
        <p className="mt-1.5 text-small text-muted">
          Cần thêm FM hoặc FS, hoặc thay người nghỉ việc: gửi yêu cầu, Admin duyệt và đặt mật khẩu
          ban đầu; tài khoản được gán vào cơ sở ngay khi duyệt. Tài khoản BOM do Admin tự tạo.
        </p>
      </div>

      {error && <DemoNotice tone="error">{error}</DemoNotice>}
      {notice && !error && <DemoNotice tone="success">{notice}</DemoNotice>}

      <section className="grid gap-4 bg-surface p-5">
        <SectionTitle>Gửi yêu cầu mới</SectionTitle>
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Loại yêu cầu">
          <Button
            size="sm"
            variant={mode === "new" ? "primary" : "secondary"}
            aria-pressed={mode === "new"}
            onClick={() => changeMode("new")}
          >
            Tuyển thêm
          </Button>
          <Button
            size="sm"
            variant={mode === "replace" ? "primary" : "secondary"}
            aria-pressed={mode === "replace"}
            onClick={() => changeMode("replace")}
          >
            Thay người nghỉ việc
          </Button>
        </div>
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="Vai trò">
          {ROLES.map((item) => (
            <Button
              key={item}
              size="sm"
              variant={role === item ? "primary" : "secondary"}
              aria-pressed={role === item}
              onClick={() => changeRole(item)}
            >
              {ACCOUNT_REQUEST_ROLE_LABEL[item]}
            </Button>
          ))}
        </div>
        {mode === "replace" && (
          <div className="grid gap-2">
            <label className={FIELD_LABEL} htmlFor="request-replaces">
              {role} nghỉ việc<span className="text-danger"> *</span>
            </label>
            <select
              id="request-replaces"
              className={INPUT}
              value={replacesId ?? ""}
              onChange={(event) =>
                setReplacesId(event.target.value === "" ? null : Number(event.target.value))
              }
            >
              <option value="">— Chọn người nghỉ việc —</option>
              {candidates.map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.label}
                </option>
              ))}
            </select>
            {candidates.length === 0 && (
              <p className="m-0 text-small text-muted">
                Không có {role} nào để thay (hoặc đã có yêu cầu thay đang chờ).
              </p>
            )}
            {replaced && (
              <p className="m-0 text-small text-muted">
                Cơ sở của tài khoản mới:{" "}
                <strong className="text-ink">{replaced.facilityCodes.join(", ")}</strong> (lấy theo
                người cũ).
              </p>
            )}
            <p className="m-0 text-small text-muted">
              Admin duyệt xong:{" "}
              {role === "FM"
                ? "FM mới thế chỗ FM cũ ở cơ sở;"
                : "FS mới nhận các cơ sở và toàn bộ việc còn mở của FS cũ (lịch hẹn, phiên bàn giao đang dở, yêu cầu hỗ trợ, yêu cầu trả kho, ca làm từ hôm nay; đơn nghỉ phép chờ duyệt bị huỷ);"}{" "}
              tài khoản cũ chuyển “Đã nghỉ việc” và không đăng nhập được nữa, lịch sử giữ nguyên.
            </p>
          </div>
        )}
        <div className="grid grid-cols-3 gap-4 max-[900px]:grid-cols-1">
          <div>
            <label className={FIELD_LABEL} htmlFor="request-name">
              Họ tên{mode === "replace" ? " người mới" : ""}
              <span className="text-danger"> *</span>
            </label>
            <input
              id="request-name"
              className={INPUT}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </div>
          <div>
            <label className={FIELD_LABEL} htmlFor="request-email">
              Email<span className="text-danger"> *</span>
            </label>
            <input
              id="request-email"
              type="email"
              className={INPUT}
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </div>
          <div>
            <label className={FIELD_LABEL} htmlFor="request-phone">
              Số điện thoại
            </label>
            <input
              id="request-phone"
              className={INPUT}
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
            />
          </div>
        </div>
        {mode === "new" && (
          <fieldset className="m-0 grid gap-2 border-0 p-0">
            <legend className={FIELD_LABEL}>
              {role === "FM" ? "Cơ sở phụ trách (chọn 1 cơ sở đang trống FM)" : "Cơ sở làm việc"}
              <span className="text-danger"> *</span>
            </legend>
            <div className="flex flex-wrap gap-2">
              {facilities.map((facility) => {
                const taken = role === "FM" && facility.fmAccountId !== null;
                const selected = facilityIds.includes(facility.id);
                return (
                  <label
                    key={facility.id}
                    className={`inline-flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-small ${
                      selected ? "border-brand text-ink" : "border-rule-strong text-muted"
                    } ${taken ? "cursor-not-allowed opacity-50" : ""}`}
                  >
                    <input
                      type={role === "FM" ? "radio" : "checkbox"}
                      name="request-facility"
                      disabled={taken}
                      checked={selected}
                      onChange={() => toggleFacility(facility.id)}
                    />
                    <span>
                      <strong className="text-ink">{facility.code}</strong> · {facility.name}
                      {taken ? ` — đã có FM ${facility.fmEmail ?? ""}` : ""}
                    </span>
                  </label>
                );
              })}
            </div>
          </fieldset>
        )}
        <div>
          <label className={FIELD_LABEL} htmlFor="request-note">
            Ghi chú cho Admin
          </label>
          <input
            id="request-note"
            className={INPUT}
            value={note}
            maxLength={500}
            onChange={(event) => setNote(event.target.value)}
          />
        </div>
        <Button className="w-fit" pending={busy === "submit"} onClick={submit}>
          <Send size={15} /> Gửi yêu cầu cho Admin
        </Button>
      </section>

      {loading ? (
        <SurfaceState variant="loading" title="Đang tải yêu cầu…" />
      ) : loadError ? (
        <SurfaceState
          variant="error"
          title="Không tải được yêu cầu"
          description={loadError}
          action={{ label: "Thử lại", onClick: () => setReloadKey((key) => key + 1) }}
        />
      ) : (
        <>
          <section className="grid gap-2">
            <SectionTitle>Đang chờ duyệt ({pending.length})</SectionTitle>
            {pending.length === 0 ? (
              <p className="m-0 text-small text-muted">Không có yêu cầu nào đang chờ.</p>
            ) : (
              pending.map((request) => (
                <AccountRequestCard
                  key={request.id}
                  request={request}
                  actions={
                    String(request.requestedById) === myId ? (
                      <Button
                        size="sm"
                        variant="secondary"
                        pending={busy === request.id}
                        onClick={() => cancel(request.id)}
                      >
                        Huỷ yêu cầu
                      </Button>
                    ) : undefined
                  }
                />
              ))
            )}
          </section>
          {done.length > 0 && (
            <section className="grid gap-2">
              <SectionTitle>Đã xử lý</SectionTitle>
              {done.map((request) => (
                <AccountRequestCard key={request.id} request={request} />
              ))}
            </section>
          )}
        </>
      )}
    </div>
  );
}
