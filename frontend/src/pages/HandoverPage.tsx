import { ArrowLeft, ArrowRight, Check, Download, FileText, MapPin } from "lucide-react";
import { useCallback, useState, type ReactNode } from "react";
import type { Navigate } from "../app/types";
import { pathId } from "../app/routes";
import { formatDate, formatDateTime, formatMoney, formatTime } from "../app/format";
import { errorMessage } from "../app/http";
import {
  ACCESS_STATUS,
  ACCESS_TYPE_LABEL,
  agreeHandoverInspection,
  cancelMyOrder,
  confirmHandover,
  fetchMyHandover,
  HANDOVER_STEP_LABEL,
  INVOICE_STATUS,
  downloadContractPdf,
  openContractDocument,
  rejectHandover,
  rotateAccessCode,
  useInvoicePayment,
  useResource,
  type AccessChoice,
  type HandoverView,
} from "../app/services/customer";
import { Button } from "../components/ui/Button";
import { ConfirmDialog } from "../components/ui/ConfirmDialog";
import { DemoNotice } from "../components/ui/DemoNotice";
import { SectionTitle } from "../components/ui/SectionTitle";
import { StatusTag } from "../components/ui/StatusTag";
import { SurfaceState } from "../components/ui/SurfaceState";

/** Thứ tự hiển thị các bước của phiên đang mở (giá trị `step` từ API). */
const STEP_ORDER = [
  "WAITING_ARRIVAL",
  "VERIFY_IDENTITY",
  "INSPECTION",
  "SIGN_CONTRACT",
  "PAY_FIRST_MONTH",
  "CUSTOMER_CONFIRM",
  "HAND_OVER_KEYS",
];

const IDENTITY_LABEL: Record<string, string> = {
  Pending: "Chưa xác minh",
  Verified: "Đã xác minh",
  Failed: "Xác minh không đạt",
};

const INSPECTION_LABEL: Record<string, string> = {
  Pending: "Chờ bạn xác nhận",
  Agreed: "Bạn đã đồng ý",
  Rejected: "Bạn đã từ chối",
};

const CLOSED_TONE: Record<string, "success" | "danger" | "neutral"> = {
  COMPLETED: "success",
  REJECTED: "danger",
  CANCELED: "neutral",
};

type Notice = { tone: "success" | "error" | "info"; message: string } | null;

/** Khách — phiên bàn giao kho (Flow 2.2–2.5). */
export function HandoverPage({ navigate }: { navigate: Navigate }) {
  const [handoverId] = useState(() => pathId(1));
  if (handoverId === null) {
    return (
      <SurfaceState
        variant="not-found"
        title="Không tìm thấy phiên bàn giao"
        action={{ label: "Về Kho của tôi", onClick: () => navigate("/my-storage") }}
      />
    );
  }
  return <Handover handoverId={handoverId} navigate={navigate} />;
}

function Handover({ handoverId, navigate }: { handoverId: number; navigate: Navigate }) {
  const load = useCallback(() => fetchMyHandover(handoverId), [handoverId]);
  const handover = useResource(load);
  const payment = useInvoicePayment(handover.reload);

  const [notice, setNotice] = useState<Notice>(null);
  const [busy, setBusy] = useState(false);
  const [docPending, setDocPending] = useState(false);
  const [pdfPending, setPdfPending] = useState(false);
  const [agreeNote, setAgreeNote] = useState("");
  const [rejecting, setRejecting] = useState(false);
  const [rejectReason, setRejectReason] = useState("");
  const [rejectConfirm, setRejectConfirm] = useState<string | null>(null);
  const [cancelConfirm, setCancelConfirm] = useState(false);
  const [accessType, setAccessType] = useState<AccessChoice | "">("");
  const [issuedCode, setIssuedCode] = useState<string | null>(null);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setNotice(null);
    try {
      await action();
    } catch (caught) {
      setNotice({ tone: "error", message: errorMessage(caught) });
    } finally {
      setBusy(false);
      handover.reload();
    }
  }

  function agree() {
    return run(async () => {
      await agreeHandoverInspection(handoverId, agreeNote.trim() || null);
      setAgreeNote("");
      setNotice({
        tone: "success",
        message: "Đã xác nhận hiện trạng khoang. Nhân viên sẽ chuẩn bị hợp đồng để bạn ký.",
      });
    });
  }

  function reject(confirmCancel: boolean) {
    setRejectConfirm(null);
    return run(async () => {
      const outcome = await rejectHandover(handoverId, rejectReason.trim(), confirmCancel);
      if (outcome.requiresConfirmation) {
        setRejectConfirm(outcome.message);
        return;
      }
      setRejecting(false);
      setRejectReason("");
      setNotice({ tone: outcome.orderCanceled ? "error" : "info", message: outcome.message });
    });
  }

  function confirm() {
    if (!accessType) return;
    return run(async () => {
      const outcome = await confirmHandover(handoverId, accessType);
      if (outcome.accessCode) setIssuedCode(outcome.accessCode);
      setNotice({ tone: "success", message: outcome.message });
    });
  }

  // Kiểu "Both": mã khoá số cấp lúc nhân viên giao chìa nên chưa hiển thị cho khách — lấy mã mới tại đây.
  function collectCode(contractId: number) {
    return run(async () => {
      const result = await rotateAccessCode(contractId);
      setIssuedCode(result.accessCode);
    });
  }

  async function cancelOrder(view: HandoverView) {
    await run(async () => {
      await cancelMyOrder(view.orderId);
      setNotice({ tone: "info", message: `Đã huỷ đơn #${view.orderId}.` });
    });
    setCancelConfirm(false);
  }

  async function downloadPdf(contractId: number) {
    setPdfPending(true);
    setNotice(null);
    try {
      await downloadContractPdf(contractId);
    } catch (caught) {
      setNotice({ tone: "error", message: errorMessage(caught) });
    } finally {
      setPdfPending(false);
    }
  }

  async function openDocument(contractId: number) {
    setDocPending(true);
    setNotice(null);
    try {
      await openContractDocument(contractId);
    } catch (caught) {
      setNotice({ tone: "error", message: errorMessage(caught) });
    } finally {
      setDocPending(false);
    }
  }

  if (handover.loading && !handover.data) {
    return (
      <div className="grid gap-3" role="status" aria-label="Đang tải">
        <div className="h-[90px] animate-pulse bg-surface" />
        <div className="h-[280px] animate-pulse bg-surface" />
      </div>
    );
  }
  if (handover.error && !handover.data) {
    const missing = handover.status === 404 || handover.status === 403;
    return (
      <SurfaceState
        variant={missing ? "not-found" : "error"}
        title={missing ? "Không tìm thấy phiên bàn giao" : "Không tải được phiên bàn giao"}
        description={handover.error}
        action={
          missing
            ? { label: "Về Kho của tôi", onClick: () => navigate("/my-storage") }
            : { label: "Thử lại", onClick: handover.reload }
        }
      />
    );
  }
  const view = handover.data;
  if (!view) return null;

  const open = view.result === "IN_PROGRESS";
  const step = view.step;
  const stepIndex = STEP_ORDER.indexOf(step === "OVERRIDE_PENDING" ? "SIGN_CONTRACT" : step);
  const canReject =
    open && (step === "INSPECTION" || step === "SIGN_CONTRACT" || step === "OVERRIDE_PENDING");
  const lastRejection = view.rejectionCount + 1 >= view.maxRejectionCount;
  const invoice = view.firstMonthInvoice;
  const accessOptions: { value: AccessChoice; label: string }[] = [];
  if (view.codeAccessEnabled) accessOptions.push({ value: "AccessCode", label: "Mã khoá số" });
  if (view.keyAccessEnabled) accessOptions.push({ value: "PhysicalKey", label: "Chìa khoá cơ" });
  if (view.codeAccessEnabled && view.keyAccessEnabled) {
    accessOptions.push({ value: "Both", label: "Cả chìa khoá cơ và mã khoá số" });
  }

  let action: ReactNode;
  if (step === "WAITING_ARRIVAL") {
    action = (
      <p className="text-small text-muted">
        Hãy đến cơ sở theo lịch hẹn. Nhân viên sẽ ghi nhận bạn đã đến và xác minh giấy tờ tuỳ thân.
      </p>
    );
  } else if (step === "VERIFY_IDENTITY") {
    action = (
      <p className="text-small text-muted">
        Nhân viên đang đối chiếu giấy tờ của bạn với tài khoản đặt kho.
        {view.identityStatus === "Failed" &&
          " Lần xác minh trước không đạt — bạn có thể quay lại với giấy tờ đúng trước hạn bàn giao."}
      </p>
    );
  } else if (step === "INSPECTION") {
    const inspectionRecorded =
      Boolean(view.inspectionNotes?.trim()) || view.inspectionPhotos.length > 0;
    action =
      view.inspectionStatus === "Pending" && !inspectionRecorded ? (
        <p className="text-small text-muted">
          Nhân viên đang kiểm tra khoang cùng bạn và ghi nhận hiện trạng (ghi chú, ảnh). Khi ghi
          nhận xong, bạn xem lại và bấm đồng ý tại đây.
        </p>
      ) : view.inspectionStatus === "Pending" ? (
        <div className="grid gap-3">
          <p className="text-small text-muted">
            Kiểm tra khoang cùng nhân viên. Nếu hiện trạng đúng như ghi nhận, bấm đồng ý để cơ sở
            lập hợp đồng.
          </p>
          <label className="grid gap-1.5 text-label text-muted">
            Lưu ý của bạn (tuỳ chọn)
            <textarea
              maxLength={1000}
              value={agreeNote}
              onChange={(event) => setAgreeNote(event.target.value)}
              className="min-h-[64px] rounded-lg border border-rule-strong bg-surface p-2.5 text-small font-normal text-ink"
            />
          </label>
          <div>
            <Button pending={busy} onClick={agree}>
              {!busy && <Check size={16} />} Đồng ý hiện trạng khoang
            </Button>
          </div>
        </div>
      ) : (
        <p className="text-small text-muted">Đang chờ cơ sở lập hợp đồng.</p>
      );
  } else if (step === "OVERRIDE_PENDING") {
    action = (
      <p className="text-small text-muted">
        Nhân viên đã đề nghị đổi mốc tính tiền
        {view.contract?.startDateOverrideRequested
          ? ` sang ${formatDate(view.contract.startDateOverrideRequested)}`
          : ""}
        . Chờ quản lý cơ sở duyệt trước khi ký hợp đồng.
      </p>
    );
  } else if (step === "SIGN_CONTRACT") {
    action = (
      <p className="text-small text-muted">
        Xem bản hợp đồng và ký tại cơ sở cùng nhân viên. Sau khi ký, hoá đơn tiền thuê tháng đầu sẽ
        được tạo.
      </p>
    );
  } else if (step === "PAY_FIRST_MONTH") {
    action = invoice ? (
      <div className="grid gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3 bg-surface-subtle p-3">
          <div>
            <span className="text-label text-muted">{invoice.code}</span>
            <strong className="block text-data text-ink">{formatMoney(invoice.amount)}</strong>
            <span className="text-label text-muted">Hạn {formatDateTime(invoice.dueDate)}</span>
          </div>
          <StatusTag tone={INVOICE_STATUS[invoice.status]?.tone ?? "neutral"}>
            {INVOICE_STATUS[invoice.status]?.label ?? invoice.status}
          </StatusTag>
        </div>
        {payment.payError && <DemoNotice tone="error">{payment.payError.message}</DemoNotice>}
        {invoice.status === "Unpaid" && (
          <div>
            <Button
              pending={payment.payingId === invoice.id}
              onClick={() => payment.pay(invoice.id)}
            >
              Thanh toán tiền thuê tháng đầu
            </Button>
          </div>
        )}
      </div>
    ) : (
      <p className="text-small text-muted">Hoá đơn tháng đầu đang được tạo.</p>
    );
  } else if (step === "CUSTOMER_CONFIRM") {
    action = (
      <div className="grid gap-3">
        <p className="text-small text-muted">
          Xác nhận biên bản bàn giao và chọn cách bạn muốn nhận quyền truy cập khoang.
        </p>
        {accessOptions.length === 0 ? (
          <DemoNotice tone="error">
            Cơ sở chưa bật hình thức truy cập nào. Vui lòng liên hệ nhân viên.
          </DemoNotice>
        ) : (
          <fieldset className="grid gap-2 border-0 p-0">
            <legend className="mb-1 text-label text-muted">Hình thức nhận quyền truy cập</legend>
            {accessOptions.map((option) => (
              <label
                key={option.value}
                className={`flex cursor-pointer items-center gap-2.5 border-2 p-3 text-small ${
                  accessType === option.value
                    ? "border-brand bg-surface-subtle text-ink"
                    : "border-rule-strong text-muted hover:text-ink"
                }`}
              >
                <input
                  type="radio"
                  name="accessType"
                  value={option.value}
                  checked={accessType === option.value}
                  onChange={() => setAccessType(option.value)}
                />
                {option.label}
              </label>
            ))}
          </fieldset>
        )}
        <div>
          <Button pending={busy} disabled={!accessType} onClick={confirm}>
            Xác nhận biên bản
          </Button>
        </div>
      </div>
    );
  } else if (step === "HAND_OVER_KEYS") {
    action = (
      <p className="text-small text-muted">
        Bạn đã chọn {ACCESS_TYPE_LABEL[view.requestedAccess ?? ""] ?? view.requestedAccess}. Nhân
        viên sẽ giao chìa khoá và hoàn tất bàn giao.
      </p>
    );
  } else if (step === "COMPLETED") {
    action = (
      <div className="grid gap-3">
        <p className="text-small text-muted">
          Bàn giao hoàn tất lúc {formatDateTime(view.completedAt)}. Hợp đồng đã có hiệu lực.
        </p>
        {view.contract && view.requestedAccess === "Both" && !issuedCode && (
          <div className="grid gap-2">
            <p className="m-0 text-small text-muted">
              Bạn đã chọn cả chìa khoá cơ và mã khoá số. Mã khoá số chưa được hiển thị — bấm để lấy
              mã (mỗi lần lấy sẽ cấp mã mới, mã cũ hết hiệu lực).
            </p>
            <div>
              <Button
                variant="secondary"
                pending={busy}
                onClick={() => view.contract && collectCode(view.contract.id)}
              >
                Lấy mã khoá số
              </Button>
            </div>
          </div>
        )}
        {view.contract && (
          <div>
            <Button onClick={() => navigate(`/my-storage/${view.contract?.id}`)}>
              Xem kho đang thuê <ArrowRight size={16} />
            </Button>
          </div>
        )}
      </div>
    );
  } else {
    action = (
      <div className="grid gap-3">
        <p className="text-small text-muted">
          {view.rejectReason ? `Lý do: ${view.rejectReason}` : "Phiên bàn giao đã đóng."}
        </p>
        <div>
          <Button variant="secondary" onClick={() => navigate("/my-storage")}>
            Về Kho của tôi
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="grid gap-6">
      <button
        className="inline-flex w-fit items-center gap-1.5 border-0 bg-transparent p-0 text-small font-semibold text-accent hover:text-ink"
        onClick={() => navigate("/my-storage")}
      >
        <ArrowLeft size={16} /> Về Kho của tôi
      </button>

      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <span className="text-label text-muted">
            Bàn giao đơn #{view.orderId}
            {view.unitCode ? ` · Khoang ${view.unitCode}` : ""}
            {view.unitType ? ` · ${view.unitType}` : ""}
          </span>
          <h1 className="mt-1 text-title text-ink">{view.facilityName}</h1>
          {view.facilityAddress && (
            <p className="mt-1.5 flex items-center gap-1.5 text-small text-muted">
              <MapPin size={15} /> {view.facilityAddress}
            </p>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <StatusTag tone={open ? "info" : (CLOSED_TONE[view.result] ?? "neutral")}>
            {HANDOVER_STEP_LABEL[step] ?? step}
          </StatusTag>
          {view.contract && (
            <Button
              variant="secondary"
              pending={docPending}
              onClick={() => view.contract && openDocument(view.contract.id)}
            >
              {!docPending && <FileText size={16} />} Xem bản hợp đồng
            </Button>
          )}
          {view.contract && (
            <Button
              variant="secondary"
              pending={pdfPending}
              onClick={() => view.contract && downloadPdf(view.contract.id)}
            >
              {!pdfPending && <Download size={16} />} Tải PDF
            </Button>
          )}
        </div>
      </div>

      {issuedCode && (
        <div className="border-2 border-brand bg-surface p-5 text-center" role="status">
          <span className="text-label text-muted">Mã khoá số của bạn — chỉ hiển thị một lần</span>
          <strong className="mt-1 block font-mono text-[2.5rem] tracking-[0.3em] text-ink">
            {issuedCode}
          </strong>
          <p className="mt-1 text-small text-muted">
            Hãy lưu lại mã này ngay. Nếu quên, bạn có thể lấy mã mới trong trang hợp đồng.
          </p>
        </div>
      )}
      {notice && <DemoNotice tone={notice.tone}>{notice.message}</DemoNotice>}

      <div className="grid grid-cols-[minmax(0,1fr)_320px] items-start gap-6 max-[980px]:grid-cols-1">
        <div className="grid gap-3">
          <section className="bg-surface p-5">
            <SectionTitle>Bước hiện tại</SectionTitle>
            <h2 className="mt-3 text-section text-ink">{HANDOVER_STEP_LABEL[step] ?? step}</h2>
            <div className="mt-3">{action}</div>

            {canReject && !rejecting && (
              <div className="mt-5">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={busy}
                  onClick={() => {
                    setRejecting(true);
                    setNotice(null);
                  }}
                >
                  Từ chối khoang này
                </Button>
              </div>
            )}
            {canReject && rejecting && (
              <div className="mt-5 bg-surface-subtle p-4">
                {lastRejection && (
                  <p className="mb-3 text-label text-danger">
                    Bạn đã từ chối {view.rejectionCount}/{view.maxRejectionCount} khoang cho phép.
                  </p>
                )}
                <label className="grid gap-1.5 text-label text-muted">
                  Lý do từ chối *
                  <textarea
                    maxLength={500}
                    value={rejectReason}
                    onChange={(event) => setRejectReason(event.target.value)}
                    className="min-h-[70px] rounded-lg border border-rule-strong bg-surface p-2.5 text-small font-normal text-ink"
                    placeholder="Ví dụ: khoang ẩm, cửa khó đóng…"
                  />
                </label>
                <div className="mt-3 flex gap-2.5">
                  <Button
                    variant="danger"
                    pending={busy}
                    disabled={!rejectReason.trim()}
                    onClick={() => reject(false)}
                  >
                    Xác nhận từ chối
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={busy}
                    onClick={() => {
                      setRejecting(false);
                      setRejectReason("");
                    }}
                  >
                    Huỷ
                  </Button>
                </div>
              </div>
            )}
          </section>

          <section className="bg-surface p-5">
            <SectionTitle>Biên bản</SectionTitle>
            <dl className="mt-4 grid grid-cols-2 gap-4 max-[760px]:grid-cols-1">
              <Field
                label="Lịch hẹn"
                value={
                  view.appointmentDate
                    ? `${formatDate(view.appointmentDate)} · ${formatTime(view.appointmentStart)} – ${formatTime(view.appointmentEnd)}`
                    : "—"
                }
              />
              <Field label="Bạn đến lúc" value={formatDateTime(view.arrivedAt)} />
              <Field label="Hạn hoàn tất bàn giao" value={formatDateTime(view.dueAt)} />
              <Field
                label="Danh tính"
                value={IDENTITY_LABEL[view.identityStatus ?? ""] ?? view.identityStatus ?? "—"}
              />
              <Field
                label="Hiện trạng khoang"
                value={
                  INSPECTION_LABEL[view.inspectionStatus ?? ""] ?? view.inspectionStatus ?? "—"
                }
              />
              <Field
                label="Số lần từ chối khoang"
                value={`${view.rejectionCount} / ${view.maxRejectionCount}`}
              />
            </dl>
            {view.inspectionNotes && (
              <p className="mt-4 whitespace-pre-line bg-surface-subtle p-3 text-small text-ink">
                {view.inspectionNotes}
              </p>
            )}
            {view.inspectionPhotos.length > 0 && (
              <div className="mt-4 grid grid-cols-4 gap-2 max-[760px]:grid-cols-2">
                {view.inspectionPhotos.map((url) => (
                  <a key={url} href={url} target="_blank" rel="noreferrer">
                    <img
                      src={url}
                      alt="Ảnh hiện trạng khoang"
                      className="h-24 w-full object-cover"
                    />
                  </a>
                ))}
              </div>
            )}
          </section>
        </div>

        <aside className="grid gap-3">
          <div className="bg-surface p-5">
            <span className="text-label text-muted">Tiến trình</span>
            {open ? (
              <ol className="mt-3 grid gap-2 pl-0">
                {STEP_ORDER.map((item, index) => {
                  const done = index < stepIndex;
                  const current = index === stepIndex;
                  return (
                    <li
                      key={item}
                      className={`flex list-none items-center gap-2.5 text-small ${
                        current ? "font-semibold text-ink" : done ? "text-muted" : "text-muted/70"
                      }`}
                    >
                      <span
                        className={`grid h-5 w-5 shrink-0 place-items-center text-label ${
                          current
                            ? "bg-brand text-signal-ink"
                            : done
                              ? "bg-success-soft text-success"
                              : "bg-surface-subtle"
                        }`}
                        aria-hidden="true"
                      >
                        {done ? <Check size={12} /> : index + 1}
                      </span>
                      {HANDOVER_STEP_LABEL[item]}
                    </li>
                  );
                })}
              </ol>
            ) : (
              <p className="mt-2 text-small text-ink">{HANDOVER_STEP_LABEL[step] ?? step}</p>
            )}
          </div>

          {view.contract && (
            <div className="bg-surface p-5">
              <span className="text-label text-muted">Hợp đồng {view.contract.code}</span>
              <dl className="mt-3 grid gap-2.5">
                <Field label="Giá thuê / tháng" value={formatMoney(view.contract.monthlyPrice)} />
                <Field label="Tiền cọc" value={formatMoney(view.contract.depositAmount)} />
                <Field
                  label="Hiệu lực"
                  value={`${formatDate(view.contract.startDate)} → ${formatDate(view.contract.endDate)}`}
                />
                <Field label="Ký lúc" value={formatDateTime(view.contract.signedAt)} />
              </dl>
            </div>
          )}

          {view.accessKeys.length > 0 && (
            <div className="bg-surface p-5">
              <span className="text-label text-muted">Quyền truy cập đã cấp</span>
              <div className="mt-3 grid gap-2">
                {view.accessKeys.map((key) => (
                  <div key={key.id} className="flex items-center justify-between gap-2 text-small">
                    <span className="text-ink">
                      {ACCESS_TYPE_LABEL[key.accessType] ?? key.accessType}
                      {key.accessType === "PhysicalKey" && key.quantity
                        ? ` · ${key.quantity} chìa`
                        : ""}
                    </span>
                    <StatusTag tone={ACCESS_STATUS[key.status]?.tone ?? "neutral"}>
                      {ACCESS_STATUS[key.status]?.label ?? key.status}
                    </StatusTag>
                  </div>
                ))}
              </div>
            </div>
          )}

          {open && (
            <div className="bg-surface-subtle p-4">
              <p className="text-label text-muted">
                Không muốn tiếp tục thuê? Bạn có thể huỷ đơn thuê này.
              </p>
              <Button
                variant="danger"
                size="sm"
                className="mt-3"
                disabled={busy}
                onClick={() => setCancelConfirm(true)}
              >
                Huỷ đơn thuê
              </Button>
            </div>
          )}
        </aside>
      </div>

      {rejectConfirm && (
        <ConfirmDialog
          title="Xác nhận từ chối và huỷ đơn"
          description={rejectConfirm}
          confirmLabel="Từ chối và huỷ đơn"
          cancelLabel="Quay lại"
          isPending={busy}
          onConfirm={() => reject(true)}
          onCancel={() => setRejectConfirm(null)}
        />
      )}
      {cancelConfirm && (
        <ConfirmDialog
          title={`Huỷ đơn #${view.orderId}?`}
          description="Phiên bàn giao, hợp đồng chưa có hiệu lực và hoá đơn chưa thanh toán sẽ bị huỷ. Huỷ do khách chủ động thì tiền cọc không được hoàn theo chính sách."
          confirmLabel="Huỷ đơn"
          cancelLabel="Giữ đơn"
          isPending={busy}
          onConfirm={() => cancelOrder(view)}
          onCancel={() => setCancelConfirm(false)}
        />
      )}
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-label text-muted">{label}</dt>
      <dd className="m-0 mt-1 text-small text-ink">{value}</dd>
    </div>
  );
}
