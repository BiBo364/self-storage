import { Pencil } from "lucide-react";
import { useEffect, useState } from "react";
import { formatDateTime, formatMoney } from "../../app/format";
import { errorMessage, errorStatus } from "../../app/http";
import {
  fetchBomPolicies,
  fetchBomUnitTypes,
  updateBomPolicy,
  updateUnitTypePrice,
  type PolicyRow,
  type UnitTypeRow,
} from "../../app/services/bom";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { SurfaceState } from "../../components/ui/SurfaceState";

const INPUT =
  "min-h-[32px] w-full min-w-0 rounded-[2px] border border-rule-strong bg-surface px-2.5 text-small text-ink outline-0 focus:border-brand";

function size(row: UnitTypeRow) {
  const parts = [row.width, row.depth, row.height].filter((value) => value !== null);
  const dims = parts.length > 0 ? `${parts.join(" × ")} m` : null;
  const area = row.area !== null ? `${row.area} m²` : null;
  return [dims, area].filter(Boolean).join(" · ") || "—";
}

/** BOM — chính sách và giá thuê. */
export function BomPricingPage({ navigate }: { navigate: Navigate }) {
  void navigate;
  const [policies, setPolicies] = useState<PolicyRow[]>([]);
  const [unitTypes, setUnitTypes] = useState<UnitTypeRow[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const [editing, setEditing] = useState<{ kind: "policy" | "price"; id: string } | null>(null);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const [policyList, typeList] = await Promise.all([fetchBomPolicies(), fetchBomUnitTypes()]);
        if (!active) return;
        setPolicies(policyList);
        setUnitTypes(typeList);
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

  function startEdit(kind: "policy" | "price", id: string, value: string) {
    setEditing({ kind, id });
    setDraft(value);
    setActionError(null);
    setNotice(null);
  }

  async function saveEdit() {
    // Enter gọi thẳng hàm này, không qua nút đang pending — chặn gửi PUT trùng.
    if (!editing || busy) return;
    setBusy(true);
    setActionError(null);
    try {
      if (editing.kind === "policy") {
        const updated = await updateBomPolicy(editing.id, draft.trim());
        setPolicies((list) => list.map((row) => (row.key === updated.key ? updated : row)));
        setNotice(`Đã cập nhật chính sách ${updated.key}.`);
      } else {
        const updated = await updateUnitTypePrice(Number(editing.id), Number(draft));
        setUnitTypes((list) => list.map((row) => (row.id === updated.id ? updated : row)));
        setNotice(`Đã cập nhật giá loại khoang ${updated.name}.`);
      }
      setEditing(null);
    } catch (caught) {
      setActionError(errorMessage(caught));
    } finally {
      setBusy(false);
    }
  }

  function editor(placeholder: string, type: "text" | "number") {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <input
          autoFocus
          type={type}
          min={type === "number" ? 0 : undefined}
          className={`${INPUT} max-w-[200px]`}
          value={draft}
          placeholder={placeholder}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") saveEdit();
            if (event.key === "Escape") setEditing(null);
          }}
        />
        <Button size="sm" pending={busy} disabled={!draft.trim()} onClick={saveEdit}>
          Lưu
        </Button>
        <Button size="sm" variant="secondary" disabled={busy} onClick={() => setEditing(null)}>
          Huỷ
        </Button>
      </div>
    );
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Chính sách & giá</h1>
        <p className="mt-1.5 text-small text-muted">
          Tham số vận hành toàn chuỗi và giá thuê theo loại khoang. Hợp đồng đã ký giữ giá chốt lúc
          ký.
        </p>
      </div>

      {actionError && <DemoNotice tone="error">{actionError}</DemoNotice>}
      {notice && !actionError && <DemoNotice tone="success">{notice}</DemoNotice>}

      {isLoading ? (
        <SurfaceState variant="loading" title="Đang tải chính sách và giá…" />
      ) : error ? (
        error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền chỉnh chính sách"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được chính sách và giá"
            description={error.message}
            action={{ label: "Thử lại", onClick: reload }}
          />
        )
      ) : (
        <>
          <section className="grid gap-3">
            <SectionTitle>Giá theo loại khoang</SectionTitle>
            {unitTypes.length === 0 ? (
              <SurfaceState variant="empty" title="Chưa có loại khoang nào" />
            ) : (
              <div className="grid gap-2">
                {unitTypes.map((row) => {
                  const isEditing = editing?.kind === "price" && editing.id === String(row.id);
                  return (
                    <div
                      key={row.id}
                      className="flex flex-wrap items-center justify-between gap-4 bg-surface p-4"
                    >
                      <div className="min-w-0">
                        <strong className="block text-small font-semibold text-ink">
                          {row.name}
                        </strong>
                        <span className="mt-1 block text-label text-muted">
                          {size(row)}
                          {row.description ? ` · ${row.description}` : ""}
                        </span>
                      </div>
                      {isEditing ? (
                        editor("Giá / tháng (đ)", "number")
                      ) : (
                        <div className="flex items-center gap-3">
                          <strong className="text-stat tabular-nums text-ink">
                            {formatMoney(row.monthlyPrice)}
                          </strong>
                          <span className="text-label text-muted">/ tháng</span>
                          <Button
                            size="sm"
                            variant="secondary"
                            disabled={busy}
                            onClick={() =>
                              startEdit("price", String(row.id), String(row.monthlyPrice ?? ""))
                            }
                          >
                            <Pencil size={13} /> Sửa giá
                          </Button>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </section>

          <section className="grid gap-3">
            <SectionTitle>Chính sách</SectionTitle>
            {policies.length === 0 ? (
              <SurfaceState variant="empty" title="Chưa có chính sách nào" />
            ) : (
              <div className="grid gap-2">
                {policies.map((row) => {
                  const isEditing = editing?.kind === "policy" && editing.id === row.key;
                  return (
                    <div
                      key={row.key}
                      className="flex flex-wrap items-center justify-between gap-4 bg-surface p-4"
                    >
                      <div className="min-w-0 max-w-[640px]">
                        <code className="text-small font-semibold text-ink">{row.key}</code>
                        {row.description && (
                          <p className="mb-0 mt-1 text-small text-muted">{row.description}</p>
                        )}
                        <span className="mt-1 block text-label text-muted">
                          Cập nhật {formatDateTime(row.updatedAt)}
                        </span>
                      </div>
                      {isEditing ? (
                        editor("Giá trị", "text")
                      ) : (
                        <div className="flex items-center gap-3">
                          <strong className="text-small font-semibold tabular-nums text-ink">
                            {row.value}
                          </strong>
                          <Button
                            size="sm"
                            variant="secondary"
                            disabled={busy}
                            onClick={() => startEdit("policy", row.key, row.value)}
                          >
                            <Pencil size={13} /> Sửa
                          </Button>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}
