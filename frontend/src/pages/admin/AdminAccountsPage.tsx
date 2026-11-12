import { Plus, Search } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { formatDateTime } from "../../app/format";
import { errorMessage, errorStatus } from "../../app/http";
import {
  ACCOUNT_STATUS_LABEL,
  ACCOUNT_STATUS_OPTIONS,
  ROLE_LABEL,
  ROLE_OPTIONS,
  createAccount,
  fetchAccounts,
  fetchPublicFacilities,
  setAccountFacilities,
  updateAccount,
  type AccountRow,
  type PublicFacility,
} from "../../app/services/admin";
import { fetchAdminStaffProfile, updateAdminStaffProfile } from "../../app/services/hr";
import type { Navigate } from "../../app/types";
import { StaffProfileEditor } from "../../components/domain/StaffProfileSection";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { DetailPanel } from "../../components/ui/DetailPanel";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { FIELD_LABEL, INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const PAGE_SIZE = 50;
const INPUT =
  "min-h-[42px] w-full rounded-lg border border-rule-strong bg-surface px-3 text-small text-ink outline-0 focus:border-brand";

function Field({
  id,
  label,
  value,
  onChange,
  type = "text",
  required,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  required?: boolean;
}) {
  return (
    <div>
      <label className={FIELD_LABEL} htmlFor={id}>
        {label}
        {required && <span className="text-danger"> *</span>}
      </label>
      <input
        id={id}
        type={type}
        className={INPUT}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  );
}

function Select({
  id,
  label,
  value,
  onChange,
  options,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <div>
      <label className={FIELD_LABEL} htmlFor={id}>
        {label}
      </label>
      <select
        id={id}
        className={INPUT}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}

const roleOptions = ROLE_OPTIONS.map((role) => ({ value: role, label: ROLE_LABEL[role] ?? role }));
const statusOptions = ACCOUNT_STATUS_OPTIONS.map((status) => ({
  value: status,
  label: ACCOUNT_STATUS_LABEL[status] ?? status,
}));

/** Admin — tài khoản. */
export function AdminAccountsPage({ navigate }: { navigate: Navigate }) {
  void navigate;
  const [role, setRole] = useState("");
  const [status, setStatus] = useState("");
  const [searchText, setSearchText] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [accounts, setAccounts] = useState<AccountRow[]>([]);
  const [facilities, setFacilities] = useState<PublicFacility[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    fetchPublicFacilities()
      .then((list) => {
        if (active) setFacilities(list);
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const list = await fetchAccounts({ role, status, q, page, size: PAGE_SIZE });
        if (active) setAccounts(list);
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [role, status, q, page, reloadKey]);

  function change(apply: () => void) {
    apply();
    setIsLoading(true);
    setError(null);
  }

  function search(event: FormEvent) {
    event.preventDefault();
    change(() => {
      setQ(searchText.trim());
      setPage(0);
    });
  }

  function replace(row: AccountRow) {
    setAccounts((list) => list.map((item) => (item.id === row.id ? row : item)));
  }

  const facilityName = (id: number) =>
    facilities.find((facility) => facility.id === id)?.name ?? `Cơ sở #${id}`;
  const filtered = role !== "" || status !== "" || q !== "";
  const openAccount = accounts.find((account) => account.id === openId) ?? null;

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Tài khoản</h1>
          <p className="mt-1.5 text-small text-muted">
            Tạo tài khoản nội bộ, đổi role, khoá / mở khoá và gán cơ sở cho FS.
          </p>
        </div>
        <Button
          onClick={() => {
            setNotice(null);
            setCreating(true);
          }}
        >
          <Plus size={16} /> Tạo tài khoản
        </Button>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-[160px]">
          <FilterSelect
            label="Role"
            value={role}
            onChange={(value) =>
              change(() => {
                setRole(value);
                setPage(0);
              })
            }
          >
            <option value="">Tất cả</option>
            {roleOptions.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </FilterSelect>
        </div>
        <div className="min-w-[170px]">
          <FilterSelect
            label="Trạng thái"
            value={status}
            onChange={(value) =>
              change(() => {
                setStatus(value);
                setPage(0);
              })
            }
          >
            <option value="">Tất cả</option>
            {statusOptions.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </FilterSelect>
        </div>
        <form className="grid min-w-[260px] flex-1 gap-1.5" onSubmit={search}>
          <label className="text-label text-muted" htmlFor="account-search">
            Tìm theo email, tên, số điện thoại
          </label>
          <div className={INPUT_WRAP}>
            <Search size={15} />
            <input
              id="account-search"
              className={INPUT_ELEMENT}
              value={searchText}
              onChange={(event) => setSearchText(event.target.value)}
              placeholder="Nhập rồi nhấn Enter"
            />
          </div>
        </form>
      </div>

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải tài khoản…" />
      ) : error ? (
        error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền quản lý tài khoản"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được tài khoản"
            description={error.message}
            action={{ label: "Thử lại", onClick: () => change(() => setReloadKey((k) => k + 1)) }}
          />
        )
      ) : accounts.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={
            page > 0
              ? "Hết dữ liệu"
              : filtered
                ? "Không có tài khoản khớp bộ lọc"
                : "Chưa có tài khoản"
          }
          action={
            page > 0
              ? { label: "Về trang đầu", onClick: () => change(() => setPage(0)) }
              : filtered
                ? {
                    label: "Xoá bộ lọc",
                    onClick: () =>
                      change(() => {
                        setRole("");
                        setStatus("");
                        setQ("");
                        setSearchText("");
                      }),
                  }
                : undefined
          }
        />
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] border-separate border-spacing-y-1.5 text-small">
              <thead>
                <tr className="bg-ink text-left text-label text-background">
                  <th className="px-3.5 py-2.5 font-semibold">Email</th>
                  <th className="px-3.5 py-2.5 font-semibold">Họ tên · SĐT</th>
                  <th className="px-3.5 py-2.5 font-semibold">Role</th>
                  <th className="px-3.5 py-2.5 font-semibold">Trạng thái</th>
                  <th className="px-3.5 py-2.5 font-semibold">Cơ sở</th>
                  <th className="px-3.5 py-2.5 font-semibold">Đăng nhập gần nhất</th>
                </tr>
              </thead>
              <tbody>
                {accounts.map((account) => (
                  <tr
                    key={account.id}
                    className="cursor-pointer bg-surface transition-colors hover:bg-surface-subtle"
                    onClick={() => {
                      setNotice(null);
                      setOpenId(account.id);
                    }}
                  >
                    <td className="px-3.5 py-3 font-medium text-ink">
                      <button
                        className="border-0 bg-transparent p-0 text-left font-medium text-ink hover:underline"
                        onClick={(event) => {
                          event.stopPropagation();
                          setNotice(null);
                          setOpenId(account.id);
                        }}
                      >
                        {account.email}
                      </button>
                      {!account.verified && (
                        <span className="ml-2 text-label text-muted">(chưa xác minh)</span>
                      )}
                    </td>
                    <td className="px-3.5 py-3 text-muted">
                      {account.name ?? "—"}
                      {account.phone ? ` · ${account.phone}` : ""}
                    </td>
                    <td className="px-3.5 py-3 text-ink">
                      {ROLE_LABEL[account.role] ?? account.role}
                    </td>
                    <td className="px-3.5 py-3">
                      <StatusTag tone={account.status === "ACTIVE" ? "success" : "danger"}>
                        {ACCOUNT_STATUS_LABEL[account.status] ?? account.status}
                      </StatusTag>
                    </td>
                    <td className="px-3.5 py-3 text-muted">
                      {account.facilityIds.length > 0
                        ? account.facilityIds.map(facilityName).join(", ")
                        : "—"}
                    </td>
                    <td className="px-3.5 py-3 text-muted">
                      {formatDateTime(account.lastLoginAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex items-center justify-end gap-3">
            <span className="text-label text-muted">Trang {page + 1}</span>
            <Button
              size="sm"
              variant="secondary"
              disabled={page === 0}
              onClick={() => change(() => setPage((p) => Math.max(0, p - 1)))}
            >
              Trang trước
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={accounts.length < PAGE_SIZE}
              onClick={() => change(() => setPage((p) => p + 1))}
            >
              Trang sau
            </Button>
          </div>
        </>
      )}

      {creating && (
        <CreatePanel
          facilities={facilities}
          onClose={() => setCreating(false)}
          onCreated={(row) => {
            setCreating(false);
            setNotice(`Đã tạo tài khoản ${row.email}.`);
            change(() => setReloadKey((k) => k + 1));
          }}
        />
      )}

      {openAccount && (
        <EditPanel
          key={openAccount.id}
          account={openAccount}
          facilities={facilities}
          onClose={() => setOpenId(null)}
          onSaved={(row, message) => {
            replace(row);
            setNotice(message);
          }}
        />
      )}
    </div>
  );
}

function CreatePanel({
  facilities,
  onClose,
  onCreated,
}: {
  facilities: PublicFacility[];
  onClose: () => void;
  onCreated: (row: AccountRow) => void;
}) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [role, setRole] = useState("FS");
  // Gán cơ sở ngay lúc tạo FS — không phải tạo xong rồi mở lại tài khoản để gán.
  const [facilityIds, setFacilityIds] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!email.trim() || !password) {
      setError("Vui lòng nhập email và mật khẩu.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      onCreated(
        await createAccount({
          email: email.trim(),
          password,
          name: name.trim() || undefined,
          phone: phone.trim() || undefined,
          role,
          facilityIds: role === "FS" && facilityIds.length > 0 ? facilityIds : undefined,
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
      title="Tạo tài khoản"
      eyebrow="Tài khoản được tạo ở trạng thái đã xác minh email"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>
            Huỷ
          </Button>
          <Button pending={busy} onClick={submit}>
            Tạo tài khoản
          </Button>
        </>
      }
    >
      <div className="grid gap-4">
        <Field
          id="new-email"
          label="Email"
          type="email"
          value={email}
          onChange={setEmail}
          required
        />
        <Field
          id="new-password"
          label="Mật khẩu (tối thiểu 8 ký tự)"
          type="password"
          value={password}
          onChange={setPassword}
          required
        />
        <Field id="new-name" label="Họ tên" value={name} onChange={setName} />
        <Field id="new-phone" label="Số điện thoại" value={phone} onChange={setPhone} />
        <Select id="new-role" label="Role" value={role} onChange={setRole} options={roleOptions} />
        {role === "FS" && (
          <div className="grid gap-2">
            <span className="text-label text-muted">Cơ sở làm việc (có thể chọn sau)</span>
            {facilities.map((facility) => (
              <label key={facility.id} className="flex items-center gap-2.5 text-small text-ink">
                <input
                  type="checkbox"
                  className="h-4 w-4 accent-[var(--brand)]"
                  checked={facilityIds.includes(facility.id)}
                  onChange={(event) =>
                    setFacilityIds((ids) =>
                      event.target.checked
                        ? [...ids, facility.id]
                        : ids.filter((id) => id !== facility.id),
                    )
                  }
                />
                {facility.name} <span className="text-label text-muted">({facility.code})</span>
              </label>
            ))}
            <p className="m-0 text-label text-muted">
              Khi bắt buộc ca đang bật, FM cần xếp ca cho FS mới trước khi phân công việc.
            </p>
          </div>
        )}
      </div>
      {error && <DemoNotice tone="error">{error}</DemoNotice>}
    </DetailPanel>
  );
}

function EditPanel({
  account,
  facilities,
  onClose,
  onSaved,
}: {
  account: AccountRow;
  facilities: PublicFacility[];
  onClose: () => void;
  onSaved: (row: AccountRow, message: string) => void;
}) {
  const [name, setName] = useState(account.name ?? "");
  const [phone, setPhone] = useState(account.phone ?? "");
  const [role, setRole] = useState(account.role);
  const [status, setStatus] = useState(account.status);
  const [facilityIds, setFacilityIds] = useState<number[]>(account.facilityIds);
  const [busy, setBusy] = useState<"profile" | "facilities" | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function saveProfile() {
    setBusy("profile");
    setError(null);
    try {
      onSaved(
        await updateAccount(account.id, {
          name: name.trim() || undefined,
          phone: phone.trim() || undefined,
          role: role !== account.role ? role : undefined,
          status: status !== account.status ? status : undefined,
        }),
        "Đã cập nhật tài khoản.",
      );
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(null);
    }
  }

  async function saveFacilities() {
    setBusy("facilities");
    setError(null);
    try {
      onSaved(await setAccountFacilities(account.id, facilityIds), "Đã gán cơ sở cho FS.");
    } catch (caught) {
      // Lưu bị từ chối thì không có gì đổi — ô chọn quay về cơ sở đang gán thật.
      setFacilityIds(account.facilityIds);
      setError(`Chưa lưu: ${errorMessage(caught)}`);
    } finally {
      setBusy(null);
    }
  }

  const unknownIds = account.facilityIds.filter(
    (id) => !facilities.some((facility) => facility.id === id),
  );

  return (
    <DetailPanel title={account.email} eyebrow={`Tài khoản #${account.id}`} onClose={onClose}>
      <dl className="m-0 grid gap-2 text-small">
        <div className="flex justify-between gap-3">
          <dt className="text-muted">Tạo lúc</dt>
          <dd className="m-0 text-ink">{formatDateTime(account.createdAt)}</dd>
        </div>
        <div className="flex justify-between gap-3">
          <dt className="text-muted">Đăng nhập gần nhất</dt>
          <dd className="m-0 text-ink">{formatDateTime(account.lastLoginAt)}</dd>
        </div>
        <div className="flex justify-between gap-3">
          <dt className="text-muted">Email</dt>
          <dd className="m-0 text-ink">{account.verified ? "Đã xác minh" : "Chưa xác minh"}</dd>
        </div>
      </dl>

      {error && <DemoNotice tone="error">{error}</DemoNotice>}

      <div className="mt-6 grid gap-4">
        <SectionTitle>Hồ sơ & quyền</SectionTitle>
        <Field id="edit-name" label="Họ tên" value={name} onChange={setName} />
        <Field id="edit-phone" label="Số điện thoại" value={phone} onChange={setPhone} />
        <Select id="edit-role" label="Role" value={role} onChange={setRole} options={roleOptions} />
        <Select
          id="edit-status"
          label="Trạng thái"
          value={status}
          onChange={setStatus}
          options={statusOptions}
        />
        <Button
          className="w-fit"
          pending={busy === "profile"}
          disabled={busy !== null}
          onClick={saveProfile}
        >
          Lưu thay đổi
        </Button>
      </div>

      {account.role === "FS" && (
        <div className="mt-8 grid gap-3">
          <SectionTitle>Cơ sở được gán</SectionTitle>
          {facilities.length === 0 ? (
            <p className="m-0 text-small text-muted">Không tải được danh sách cơ sở.</p>
          ) : (
            facilities.map((facility) => (
              <label key={facility.id} className="flex items-center gap-2.5 text-small text-ink">
                <input
                  type="checkbox"
                  className="h-4 w-4 accent-[var(--brand)]"
                  checked={facilityIds.includes(facility.id)}
                  onChange={(event) =>
                    setFacilityIds((ids) =>
                      event.target.checked
                        ? [...ids, facility.id]
                        : ids.filter((id) => id !== facility.id),
                    )
                  }
                />
                {facility.name} <span className="text-label text-muted">({facility.code})</span>
              </label>
            ))
          )}
          {unknownIds.length > 0 && (
            <p className="m-0 text-label text-muted">
              Còn gán cơ sở không có trong danh sách cơ sở đang hoạt động:{" "}
              {unknownIds.map((id) => `#${id}`).join(", ")}. Lưu sẽ giữ nguyên các cơ sở này.
            </p>
          )}
          <Button
            variant="secondary"
            className="w-fit"
            pending={busy === "facilities"}
            disabled={busy !== null}
            onClick={saveFacilities}
          >
            Lưu cơ sở
          </Button>
        </div>
      )}

      {(account.role === "FS" || account.role === "FM") && (
        <div className="mt-8">
          <StaffProfileEditor
            key={account.id}
            load={() => fetchAdminStaffProfile(account.id)}
            save={(input) => updateAdminStaffProfile(account.id, input)}
          />
        </div>
      )}
    </DetailPanel>
  );
}
