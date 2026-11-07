import { ArrowLeft, Check, RefreshCw } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { formatDate, formatDateTime, formatMoney } from "../../app/format";
import { errorMessage, errorStatus } from "../../app/http";
import { pathId } from "../../app/routes";
import {
  arriveAppointment,
  confirmCheckout,
  fetchCheckout,
  saveCheckoutInspection,
  type CheckoutView,
} from "../../app/services/staff";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { ConfirmDialog } from "../../components/ui/ConfirmDialog";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { FIELD_LABEL } from "../../components/ui/FormField";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";
import {
  FileUploadField,
  INPUT_CLASS,
  TEXTAREA_CLASS,
  attachUploaded,
  filesFromUrls,
  type PickedFile,
} from "../../components/domain/FileUploadField";

const RESULT: Record<string, { label: string; tone: "success" | "warning" | "info" | "neutral" }> =
  {
    IN_PROGRESS: { label: "Sẵn sàng chốt", tone: "info" },
    PENDING_ITEMS: { label: "Khoang còn đồ", tone: "warning" },
    AWAITING_PAYMENT: { label: "Chờ khách trả phần thiếu", tone: "warning" },
    COMPLETED: { label: "Đã hoàn tất", tone: "success" },
  };

function resultTag(result: string | null) {
  if (!result) return { label: "Chưa kiểm tra", tone: "neutral" as const };
  return RESULT[result] ?? { label: result, tone: "neutral" as const };
}

function BackLink({ navigate }: { navigate: Navigate }) {
  return (
    <button
      className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-label font-semibold text-muted hover:text-ink"
      onClick={() => navigate("/staff/returns")}
    >
      <ArrowLeft size={15} /> Về danh sách trả kho
    </button>
  );
}

function Row({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-3">
      <dt className="text-muted">{term}</dt>
      <dd className="m-0 text-right text-ink">{children}</dd>
    </div>
  );
}

type Form = {
  unitEmptied: boolean;
  cleaningRequired: boolean;
  damageAmount: string;
  damages: string;
  returnedKeys: string;
  note: string;
};

function formFrom(view: CheckoutView): Form {
  return {
    unitEmptied: view.unitEmptied,
    cleaningRequired: view.cleaningRequired,
    damageAmount: view.damageAmount ? String(view.damageAmount) : "",
    damages: view.damages ?? "",
    returnedKeys: view.returnedKeys === null ? "" : String(view.returnedKeys),
    note: view.note ?? "",
  };
}

/** Phí dự kiến từ biên bản đã lưu — số chốt cuối cùng do backend tính khi xác nhận. */
function estimate(view: CheckoutView) {
  const cleaning = view.cleaningRequired ? Number(view.cleaningFee ?? 0) : 0;
  const damage = Number(view.damageAmount ?? 0);
  const missingKeys = Math.max(0, view.keysIssued - (view.returnedKeys ?? 0));
  const lostKeys = missingKeys * Number(view.lostKeyFee ?? 0);
  const unpaid = Number(view.unpaidContractInvoices ?? 0);
  const total = cleaning + damage + lostKeys + unpaid;
  return { cleaning, damage, missingKeys, lostKeys, unpaid, total };
}

/** FS — biên bản trả kho. */
export function ReturnCheckoutPage({ navigate }: { navigate: Navigate }) {
  const returnRequestId = pathId(2);
  const [view, setView] = useState<CheckoutView | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const [form, setForm] = useState<Form | null>(null);
  const [photos, setPhotos] = useState<PickedFile[]>([]);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        if (returnRequestId === null) return;
        const next = await fetchCheckout(returnRequestId);
        if (!active) return;
        setView(next);
        setForm(formFrom(next));
        setPhotos(filesFromUrls(next.photos));
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [returnRequestId, reloadKey]);

  function reload() {
    setIsLoading(true);
    setError(null);
    setActionError(null);
    setNotice(null);
    setReloadKey((key) => key + 1);
  }

  async function run(task: () => Promise<CheckoutView | null>, success: string) {
    if (returnRequestId === null) return false;
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const next = (await task()) ?? (await fetchCheckout(returnRequestId));
      setView(next);
      setNotice(success);
      return true;
    } catch (caught) {
      setActionError(errorMessage(caught));
      return false;
    } finally {
      setBusy(false);
    }
  }

  if (isLoading) {
    return (
      <div className="grid gap-6">
        <BackLink navigate={navigate} />
        <SurfaceState variant="loading" title="Đang tải biên bản trả kho…" />
      </div>
    );
  }

  if (error || !view || !form || returnRequestId === null) {
    return (
      <div className="grid gap-6">
        <BackLink navigate={navigate} />
        {error?.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xem biên bản này"
            description={error.message}
          />
        ) : error && error.status !== 404 ? (
          <SurfaceState
            variant="error"
            title="Không tải được biên bản trả kho"
            description={error.message}
            action={{ label: "Thử lại", onClick: reload }}
          />
        ) : (
          <SurfaceState
            variant="not-found"
            title="Không tìm thấy yêu cầu trả kho"
            description={error?.message ?? "Đường dẫn không hợp lệ."}
            action={{ label: "Về danh sách trả kho", onClick: () => navigate("/staff/returns") }}
          />
        )}
      </div>
    );
  }

  const current = view;
  const request = current.returnRequest;
  const tag = resultTag(current.result);
  const fees = estimate(current);
  const editable =
    current.arrivedAt !== null &&
    current.result !== "COMPLETED" &&
    current.result !== "AWAITING_PAYMENT";
  const balance = Number(current.depositAmount ?? 0) - fees.total;

  async function saveInspection() {
    if (!form || returnRequestId === null) return;
    const damageAmount = form.damageAmount.trim() === "" ? undefined : Number(form.damageAmount);
    const returnedKeys = form.returnedKeys.trim() === "" ? undefined : Number(form.returnedKeys);
    const ok = await run(
      () =>
        saveCheckoutInspection(returnRequestId, {
          unitEmptied: form.unitEmptied,
          cleaningRequired: form.cleaningRequired,
          damageAmount,
          damages: form.damages.trim() || undefined,
          returnedKeys,
          photos: photos.map((photo) => photo.url),
          note: form.note.trim() || undefined,
        }),
      form.unitEmptied
        ? "Đã lưu kết quả kiểm tra."
        : "Đã lưu: khoang còn đồ, quyền truy cập của khách vẫn giữ.",
    );
    if (ok) {
      await attachUploaded(photos);
      setPhotos((list) => list.map((item) => ({ ...item, batchId: null })));
    }
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <BackLink navigate={navigate} />
          <h1 className="mb-0 mt-3 text-title text-ink">Biên bản trả kho</h1>
          <p className="mt-1.5 text-small text-muted">
            Khoang {request.unitCode ?? "—"} · Yêu cầu #{request.id}
          </p>
        </div>
        <StatusTag tone={tag.tone}>{tag.label}</StatusTag>
      </div>

      {actionError && <DemoNotice tone="error">{actionError}</DemoNotice>}
      {notice && !actionError && <DemoNotice tone="success">{notice}</DemoNotice>}

      <div className="grid grid-cols-[320px_minmax(0,1fr)] items-start gap-6 max-[980px]:grid-cols-1">
        <aside className="grid gap-4">
          <div className="bg-surface p-5">
            <span className="text-label text-muted">Thông tin buổi trả kho</span>
            <dl className="mb-0 mt-3 grid gap-2 text-small">
              <Row term="Khoang">
                <strong>{request.unitCode ?? "—"}</strong>
              </Row>
              <Row term="Lịch hẹn">{formatDateTime(request.appointmentStart)}</Row>
              <Row term="Khách chọn ngày">{formatDate(request.preferredDate)}</Row>
              <Row term="Khách đến">{formatDateTime(current.arrivedAt)}</Row>
              <Row term="Số chìa đã giao">{current.keysIssued}</Row>
              <Row term="Tiền cọc">{formatMoney(current.depositAmount)}</Row>
            </dl>
            {request.note && (
              <p className="mb-0 mt-3 text-label text-muted">Ghi chú của khách: {request.note}</p>
            )}
          </div>

          <div className="bg-surface p-5">
            <span className="text-label text-muted">Phí dự kiến (theo biên bản đã lưu)</span>
            <dl className="mb-0 mt-3 grid gap-2 text-small">
              <Row term="Vệ sinh">
                {current.cleaningRequired ? formatMoney(fees.cleaning) : "Không áp dụng"}
              </Row>
              <Row term="Hư hỏng">{formatMoney(fees.damage)}</Row>
              <Row term={`Chìa thiếu (${fees.missingKeys})`}>{formatMoney(fees.lostKeys)}</Row>
              <Row term="Hoá đơn chưa thanh toán">{formatMoney(fees.unpaid)}</Row>
              <Row term="Tạm tính">
                <strong>{formatMoney(fees.total)}</strong>
              </Row>
              <Row term="Tiền cọc">{formatMoney(current.depositAmount)}</Row>
              <Row term={balance >= 0 ? "Dự kiến hoàn" : "Dự kiến thu thêm"}>
                <strong>{formatMoney(Math.abs(balance))}</strong>
              </Row>
            </dl>
            <p className="mb-0 mt-3 text-label text-muted">
              Phí vệ sinh {formatMoney(current.cleaningFee)} · mất chìa{" "}
              {formatMoney(current.lostKeyFee)}/chìa. Số chốt do hệ thống tính khi xác nhận.
            </p>
          </div>
        </aside>

        <section className="bg-surface p-5">
          {current.result === "COMPLETED" && (
            <div>
              <div className="bg-success-soft p-5 text-success">
                <Check size={26} />
                <h2 className="mb-0 mt-3 text-section">Đã hoàn tất trả kho</h2>
                <p className="mb-0 mt-1 text-small">
                  Hoàn tất lúc {formatDateTime(current.completedAt)}.
                </p>
              </div>
              <dl className="mb-0 mt-4 grid gap-2 bg-surface-subtle p-4 text-small">
                <Row term="Tổng phí">{formatMoney(current.totalCharges)}</Row>
                <Row term="Tiền hoàn cho khách">{formatMoney(current.refundAmount)}</Row>
              </dl>
              <Button className="mt-4" onClick={() => navigate("/staff/returns")}>
                Về danh sách trả kho
              </Button>
            </div>
          )}

          {current.result === "AWAITING_PAYMENT" && (
            <div>
              <h2 className="m-0 text-section text-ink">Chờ khách thanh toán phần còn thiếu</h2>
              <dl className="mb-0 mt-4 grid gap-2 bg-surface-subtle p-4 text-small">
                <Row term="Tổng phí">{formatMoney(current.totalCharges)}</Row>
                <Row term="Hoá đơn">{current.shortfallInvoice?.code ?? "—"}</Row>
                <Row term="Số tiền">
                  <strong>{formatMoney(current.shortfallInvoice?.amount)}</strong>
                </Row>
                <Row term="Hạn thanh toán">
                  {formatDate(current.shortfallInvoice?.dueDate ?? null)}
                </Row>
              </dl>
              <div className="mt-4 bg-info-soft p-4 text-info">
                <strong className="block text-small">Chờ khách thanh toán trên tài khoản</strong>
                <p className="mb-0 mt-1 text-small">
                  Trả kho hoàn tất khi hoá đơn phần còn thiếu được thanh toán.
                </p>
                <Button variant="secondary" size="sm" className="mt-3" onClick={reload}>
                  <RefreshCw size={14} /> Làm mới trạng thái
                </Button>
              </div>
            </div>
          )}

          {current.arrivedAt === null && current.result !== "COMPLETED" && (
            <div>
              <h2 className="m-0 text-section text-ink">Khách chưa đến</h2>
              <p className="mt-2 text-small text-muted">
                Xác nhận khách đã đến trước khi kiểm tra khoang.
              </p>
              {request.appointmentId !== null && (
                <Button
                  className="mt-4"
                  pending={busy}
                  onClick={() =>
                    run(async () => {
                      await arriveAppointment(request.appointmentId as number);
                      return null;
                    }, "Đã xác nhận khách đến.")
                  }
                >
                  Khách đã đến
                </Button>
              )}
            </div>
          )}

          {editable && (
            <div>
              <h2 className="m-0 text-section text-ink">Kiểm tra khoang</h2>
              <p className="mt-2 text-small leading-relaxed text-muted">
                Khoang còn đồ thì lưu “chưa trống” — khách giữ quyền truy cập, bạn kiểm lại trên
                cùng biên bản.
              </p>
              <div className="mt-4 grid gap-3">
                <label className="flex items-center gap-2.5 text-small text-ink">
                  <input
                    type="checkbox"
                    className="h-4 w-4 accent-[var(--brand)]"
                    checked={form.unitEmptied}
                    onChange={(event) => setForm({ ...form, unitEmptied: event.target.checked })}
                  />
                  Khoang đã trống
                </label>
                <label className="flex items-center gap-2.5 text-small text-ink">
                  <input
                    type="checkbox"
                    className="h-4 w-4 accent-[var(--brand)]"
                    checked={form.cleaningRequired}
                    onChange={(event) =>
                      setForm({ ...form, cleaningRequired: event.target.checked })
                    }
                  />
                  Cần vệ sinh ({formatMoney(current.cleaningFee)})
                </label>
              </div>
              <div className="mt-4 grid grid-cols-2 gap-4 max-[760px]:grid-cols-1">
                <div>
                  <label className={FIELD_LABEL} htmlFor="damage-amount">
                    Phí hư hỏng (đ)
                  </label>
                  <input
                    id="damage-amount"
                    type="number"
                    min={0}
                    step={1000}
                    className={INPUT_CLASS}
                    value={form.damageAmount}
                    onChange={(event) => setForm({ ...form, damageAmount: event.target.value })}
                    placeholder="0"
                  />
                </div>
                <div>
                  <label className={FIELD_LABEL} htmlFor="returned-keys">
                    Số chìa khách trả (đã giao {current.keysIssued})
                  </label>
                  <input
                    id="returned-keys"
                    type="number"
                    min={0}
                    className={INPUT_CLASS}
                    value={form.returnedKeys}
                    onChange={(event) => setForm({ ...form, returnedKeys: event.target.value })}
                    placeholder="0"
                  />
                </div>
              </div>
              <label className={`${FIELD_LABEL} mt-4`} htmlFor="damages">
                Mô tả hư hỏng
              </label>
              <textarea
                id="damages"
                className={TEXTAREA_CLASS}
                value={form.damages}
                maxLength={2000}
                onChange={(event) => setForm({ ...form, damages: event.target.value })}
              />
              <label className={`${FIELD_LABEL} mt-4`} htmlFor="checkout-note">
                Ghi chú
              </label>
              <textarea
                id="checkout-note"
                className={TEXTAREA_CLASS}
                value={form.note}
                maxLength={2000}
                onChange={(event) => setForm({ ...form, note: event.target.value })}
              />
              <div className="mt-3">
                <FileUploadField
                  label="Ảnh hiện trạng khi trả (JPEG/PNG/WebP/PDF, ≤ 10MB, tối đa 20)"
                  files={photos}
                  onChange={setPhotos}
                />
              </div>
              {current.inspectedAt && (
                <p className="mb-0 mt-3 text-label text-muted">
                  Lần kiểm tra gần nhất: {formatDateTime(current.inspectedAt)}
                </p>
              )}
              <div className="mt-5 flex flex-wrap gap-2">
                <Button variant="secondary" pending={busy} onClick={saveInspection}>
                  Lưu kết quả kiểm tra
                </Button>
                {current.result === "IN_PROGRESS" && (
                  <Button disabled={busy} onClick={() => setConfirmOpen(true)}>
                    <Check size={16} /> Chốt trả kho
                  </Button>
                )}
              </div>
            </div>
          )}
        </section>
      </div>

      {confirmOpen && (
        <ConfirmDialog
          title="Chốt biên bản trả kho"
          description={`Tạm tính phí ${formatMoney(fees.total)} · tiền cọc ${formatMoney(current.depositAmount)} → ${
            balance >= 0
              ? `dự kiến hoàn ${formatMoney(balance)}`
              : `dự kiến thu thêm ${formatMoney(-balance)}`
          }. Hệ thống thu hồi quyền truy cập và đối trừ cọc; không hoàn tác được.`}
          confirmLabel="Chốt trả kho"
          tone="default"
          isPending={busy}
          onCancel={() => setConfirmOpen(false)}
          onConfirm={async () => {
            await run(() => confirmCheckout(returnRequestId), "Đã chốt biên bản trả kho.");
            setConfirmOpen(false);
          }}
        />
      )}
    </div>
  );
}
