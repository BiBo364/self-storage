import { Pencil } from "lucide-react";
import { useEffect, useState } from "react";
import { errorMessage, errorStatus } from "../../app/http";
import {
  ROLE_LABEL,
  ROLE_OPTIONS,
  fetchPermissionMatrix,
  setRolePermissions,
  type PermissionRow,
} from "../../app/services/admin";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { SurfaceState } from "../../components/ui/SurfaceState";

const SCOPE_LABEL: Record<string, string> = {
  GLOBAL: "Toàn hệ thống",
  FACILITY: "Theo cơ sở",
};

/** ADMIN là role-bound — backend không cho chỉnh qua ma trận. */
const READ_ONLY_ROLE = "ADMIN";

/** Admin — ma trận phân quyền. */
export function AdminPermissionsPage({ navigate }: { navigate: Navigate }) {
  void navigate;
  const [rows, setRows] = useState<PermissionRow[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const [editingRole, setEditingRole] = useState<string | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const matrix = await fetchPermissionMatrix();
        if (active) setRows(matrix);
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

  const roles = [
    ...ROLE_OPTIONS,
    ...[...new Set(rows.flatMap((row) => Object.keys(row.scopeByRole)))].filter(
      (role) => !(ROLE_OPTIONS as readonly string[]).includes(role),
    ),
  ];

  function startEdit(role: string) {
    const current: Record<string, string> = {};
    rows.forEach((row) => {
      const scope = row.scopeByRole[role];
      if (scope) current[row.code] = scope;
    });
    setDraft(current);
    setEditingRole(role);
    setActionError(null);
    setNotice(null);
  }

  const changedCount = editingRole
    ? rows.filter((row) => (row.scopeByRole[editingRole] ?? "") !== (draft[row.code] ?? "")).length
    : 0;

  async function save() {
    if (!editingRole) return;
    setBusy(true);
    setActionError(null);
    try {
      const matrix = await setRolePermissions(editingRole, draft);
      setRows(matrix);
      setNotice(`Đã cập nhật quyền của ${ROLE_LABEL[editingRole] ?? editingRole}.`);
      setEditingRole(null);
    } catch (caught) {
      setActionError(errorMessage(caught));
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Phân quyền</h1>
          <p className="mt-1.5 text-small text-muted">
            Ma trận permission × role. Chọn một role để chỉnh; lưu sẽ thay toàn bộ quyền của role
            đó. Quyền Admin cố định theo role, không chỉnh ở đây.
          </p>
        </div>
        {editingRole && (
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-label text-muted">
              Đang sửa {ROLE_LABEL[editingRole] ?? editingRole} · {changedCount} thay đổi
            </span>
            <Button variant="secondary" disabled={busy} onClick={() => setEditingRole(null)}>
              Huỷ
            </Button>
            <Button disabled={changedCount === 0} onClick={() => setConfirming(true)}>
              Lưu quyền
            </Button>
          </div>
        )}
      </div>

      {actionError && <DemoNotice tone="error">{actionError}</DemoNotice>}
      {notice && !actionError && <DemoNotice tone="success">{notice}</DemoNotice>}

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải ma trận phân quyền…" />
      ) : error ? (
        error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xem phân quyền"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được ma trận phân quyền"
            description={error.message}
            action={{ label: "Thử lại", onClick: reload }}
          />
        )
      ) : rows.length === 0 ? (
        <SurfaceState variant="empty" title="Chưa có permission nào trong hệ thống" />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[960px] border-separate border-spacing-y-1.5 text-small">
            <thead>
              <tr className="bg-ink text-left text-label text-background">
                <th className="px-3.5 py-2.5 font-semibold">Permission</th>
                {roles.map((role) => (
                  <th key={role} className="px-3.5 py-2.5 font-semibold">
                    <div className="flex items-center gap-2">
                      {ROLE_LABEL[role] ?? role}
                      {role !== READ_ONLY_ROLE && editingRole === null && (
                        <button
                          className="grid h-6 w-6 place-items-center border-0 bg-transparent text-background hover:text-brand"
                          aria-label={`Sửa quyền ${ROLE_LABEL[role] ?? role}`}
                          onClick={() => startEdit(role)}
                        >
                          <Pencil size={13} />
                        </button>
                      )}
                    </div>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.code} className="bg-surface">
                  <td className="px-3.5 py-2.5 align-top">
                    <code className="text-small font-semibold text-ink">{row.code}</code>
                    {row.description && (
                      <span className="mt-0.5 block text-label text-muted">{row.description}</span>
                    )}
                  </td>
                  {roles.map((role) => {
                    if (role === editingRole) {
                      return (
                        <td key={role} className="bg-surface-subtle px-3.5 py-2.5">
                          <select
                            aria-label={`${row.code} — ${role}`}
                            className="min-h-[32px] w-full rounded-[2px] border border-rule-strong bg-surface px-2 text-small text-ink outline-0 focus:border-brand"
                            value={draft[row.code] ?? ""}
                            onChange={(event) =>
                              setDraft((current) => {
                                const next = { ...current };
                                if (event.target.value) next[row.code] = event.target.value;
                                else delete next[row.code];
                                return next;
                              })
                            }
                          >
                            <option value="">Không có</option>
                            <option value="GLOBAL">{SCOPE_LABEL.GLOBAL}</option>
                            <option value="FACILITY">{SCOPE_LABEL.FACILITY}</option>
                          </select>
                        </td>
                      );
                    }
                    const scope = row.scopeByRole[role];
                    return (
                      <td key={role} className="px-3.5 py-2.5 text-muted">
                        {scope ? (
                          <span
                            className={scope === "GLOBAL" ? "font-semibold text-ink" : "text-ink"}
                          >
                            {SCOPE_LABEL[scope] ?? scope}
                          </span>
                        ) : (
                          "—"
                        )}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {confirming && editingRole && (
        <ConfirmDialog
          title={`Lưu quyền của ${ROLE_LABEL[editingRole] ?? editingRole}?`}
          description={`${changedCount} permission thay đổi. Toàn bộ quyền của role được thay bằng cột đang sửa.`}
          confirmLabel="Lưu quyền"
          tone="default"
          isPending={busy}
          onCancel={() => setConfirming(false)}
          onConfirm={save}
        />
      )}
    </div>
  );
}
