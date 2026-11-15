import { Check, X } from "lucide-react";
import { useEffect, useState } from "react";
import { errorMessage } from "../../app/http";
import {
  approveAccountRequest,
  fetchAdminAccountRequests,
  rejectAccountRequest,
} from "../../app/services/admin";
import type { AccountRequestRow } from "../../app/services/bom";
import { AccountRequestCard } from "../../components/domain/AccountRequestCard";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { SurfaceState } from "../../components/ui/SurfaceState";

const INPUT =
  "min-h-[36px] w-full min-w-0 max-w-[260px] rounded-lg border border-rule-strong bg-surface px-2.5 text-small text-ink outline-0 focus:border-brand";

type Mode = { id: number; kind: "approve" | "reject" } | null;

/** ADMIN — duyệt yêu cầu tạo tài khoản do BOM gửi: đặt mật khẩu ban đầu hoặc từ chối kèm lý do. */
export function AdminAccountRequestsPage() {
  const [tab, setTab] = useState<"Pending" | "all">("Pending");
  const [requests, setRequests] = useState<AccountRequestRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [mode, setMode] = useState<Mode>(null);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    fetchAdminAccountRequests(tab === "Pending" ? "Pending" : undefined)
      .then((list) => {
        if (!active) return;
        setRequests(list);
        setLoadError(null);
      })
      .catch((caught) => active && setLoadError(errorMessage(caught)))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [tab, reloadKey]);

  function open(id: number, kind: "approve" | "reject") {
    setMode({ id, kind });
    setDraft("");
    setError(null);
    setNotice(null);
  }

  async function confirm(request: AccountRequestRow) {
    if (!mode) return;
    setBusy(true);
    setError(null);
    try {
      const updated =
        mode.kind === "approve"
          ? await approveAccountRequest(request.id, draft)
          : await rejectAccountRequest(request.id, draft.trim());
      setRequests((list) =>
        tab === "Pending"
          ? list.filter((item) => item.id !== request.id)
          : list.map((item) => (item.id === request.id ? updated : item)),
      );
      setNotice(
        mode.kind === "approve"
          ? `Đã tạo tài khoản ${updated.email}. Hãy gửi mật khẩu ban đầu cho ${updated.name}.`
          : `Đã từ chối yêu cầu của ${updated.name}.`,
      );
      setMode(null);
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(false);
    }
  }

  function changeTab(next: "Pending" | "all") {
    setTab(next);
    setLoading(true);
    setMode(null);
  }

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="m-0 text-title text-ink">Yêu cầu tạo tài khoản</h1>
        <p className="mt-1.5 text-small text-muted">
          BOM gửi yêu cầu tạo tài khoản FM / FS. Duyệt = tạo tài khoản với mật khẩu ban đầu bạn đặt
          (người dùng nhận email báo tài khoản đã sẵn sàng, không kèm mật khẩu) và gán cơ sở ngay.
          Yêu cầu thay người nghỉ việc: duyệt xong việc của người cũ chuyển sang tài khoản mới và
          tài khoản cũ chuyển “Đã nghỉ việc”.
        </p>
      </div>

      <div className="flex gap-2" role="tablist" aria-label="Lọc yêu cầu">
        <Button
          size="sm"
          variant={tab === "Pending" ? "primary" : "secondary"}
          role="tab"
          aria-selected={tab === "Pending"}
          onClick={() => changeTab("Pending")}
        >
          Chờ duyệt
        </Button>
        <Button
          size="sm"
          variant={tab === "all" ? "primary" : "secondary"}
          role="tab"
          aria-selected={tab === "all"}
          onClick={() => changeTab("all")}
        >
          Tất cả
        </Button>
      </div>

      {error && <DemoNotice tone="error">{error}</DemoNotice>}
      {notice && !error && <DemoNotice tone="success">{notice}</DemoNotice>}

      {loading ? (
        <SurfaceState variant="loading" title="Đang tải yêu cầu…" />
      ) : loadError ? (
        <SurfaceState
          variant="error"
          title="Không tải được yêu cầu"
          description={loadError}
          action={{ label: "Thử lại", onClick: () => setReloadKey((key) => key + 1) }}
        />
      ) : requests.length === 0 ? (
        <SurfaceState
          variant="empty"
          title={tab === "Pending" ? "Không có yêu cầu nào đang chờ" : "Chưa có yêu cầu nào"}
        />
      ) : (
        <div className="grid gap-2">
          {requests.map((request) => {
            const editing = mode?.id === request.id;
            return (
              <AccountRequestCard
                key={request.id}
                request={request}
                actions={
                  request.status !== "Pending" ? undefined : editing ? (
                    <>
                      {mode.kind === "approve" && request.replacesAccountId !== null && (
                        <p className="m-0 w-full text-small text-warning">
                          Duyệt sẽ chuyển việc còn mở của{" "}
                          {request.replacesName ?? request.replacesEmail} sang tài khoản mới và khoá
                          tài khoản cũ (Đã nghỉ việc).
                        </p>
                      )}
                      <input
                        autoFocus
                        aria-label={mode.kind === "approve" ? "Mật khẩu ban đầu" : "Lý do từ chối"}
                        type="text"
                        className={INPUT}
                        placeholder={
                          mode.kind === "approve" ? "Mật khẩu ban đầu (≥ 8 ký tự)" : "Lý do từ chối"
                        }
                        value={draft}
                        onChange={(event) => setDraft(event.target.value)}
                        onKeyDown={(event) => {
                          if (event.key === "Enter") confirm(request);
                          if (event.key === "Escape") setMode(null);
                        }}
                      />
                      <Button
                        size="sm"
                        variant={mode.kind === "approve" ? "primary" : "danger"}
                        pending={busy}
                        disabled={
                          mode.kind === "approve" ? draft.length < 8 : draft.trim().length === 0
                        }
                        onClick={() => confirm(request)}
                      >
                        {mode.kind === "approve" ? "Tạo tài khoản" : "Từ chối"}
                      </Button>
                      <Button
                        size="sm"
                        variant="secondary"
                        disabled={busy}
                        onClick={() => setMode(null)}
                      >
                        Huỷ
                      </Button>
                    </>
                  ) : (
                    <>
                      <Button size="sm" onClick={() => open(request.id, "approve")}>
                        <Check size={14} /> Duyệt
                      </Button>
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() => open(request.id, "reject")}
                      >
                        <X size={14} /> Từ chối
                      </Button>
                    </>
                  )
                }
              />
            );
          })}
        </div>
      )}
    </div>
  );
}
