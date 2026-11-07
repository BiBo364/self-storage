import { ArrowLeft, Check, FileText, RefreshCw } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { formatDate, formatDateTime, formatMoney, formatTime, isoDate } from "../../app/format";
import { errorMessage, errorStatus } from "../../app/http";
import { pathId, queryParam } from "../../app/routes";
import {
  correctHandoverInspection,
  fetchContractDocumentUrl,
  fetchStaffHandover,
  fetchStaffHandovers,
  handOverKeys,
  handoverStep,
  markHandoverNotSigned,
  requestStartDateOverride,
  saveHandoverInspection,
  signContract,
  verifyHandoverIdentity,
  type HandoverView,
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

// ---------------------------------------------------------------- trang checklist

const STEP_ORDER = [
  { key: "VERIFY_IDENTITY", title: "Xác minh danh tính" },
  { key: "INSPECTION", title: "Kiểm tra khoang" },
  { key: "SIGN_CONTRACT", title: "Ký hợp đồng" },
  { key: "PAY_FIRST_MONTH", title: "Thanh toán tháng đầu" },
  { key: "CUSTOMER_CONFIRM", title: "Khách xác nhận biên bản" },
  { key: "HAND_OVER_KEYS", title: "Giao chìa khoá" },
];

const ACCESS_LABEL: Record<string, string> = {
  AccessCode: "Mã số",
  PhysicalKey: "Chìa khoá cơ",
  Both: "Chìa khoá cơ + mã số",
};

const STATUS_LABEL: Record<string, string> = {
  Pending: "Đang chờ",
  Verified: "Đạt",
  Failed: "Không đạt",
  Agreed: "Khách đồng ý",
  Rejected: "Từ chối",
  Approved: "Đã duyệt",
  Draft: "Bản nháp",
  Signed: "Đã ký",
  Active: "Đang hiệu lực",
  Canceled: "Đã huỷ",
  Unpaid: "Chưa thanh toán",
  Paid: "Đã thanh toán",
  Expired: "Quá hạn",
  Revoked: "Đã thu hồi",
  Lost: "Mất",
};

function label(value: string | null | undefined) {
  if (!value) return "—";
  return STATUS_LABEL[value] ?? value;
}

function stepIndex(step: string) {
  if (step === "OVERRIDE_PENDING") return 2;
  const index = STEP_ORDER.findIndex((item) => item.key === step);
  return index;
}

function BackLink({ navigate }: { navigate: Navigate }) {
  return (
    <button
      className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-label font-semibold text-muted hover:text-ink"
      onClick={() => navigate("/staff/handovers")}
    >
      <ArrowLeft size={15} /> Về đơn đang bàn giao
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

function WaitingCustomer({
  title,
  description,
  onRefresh,
  refreshing,
}: {
  title: string;
  description: string;
  onRefresh: () => void;
  refreshing: boolean;
}) {
  return (
    <div className="mt-4 bg-info-soft p-4 text-info">
      <strong className="block text-small">{title}</strong>
      <p className="mb-0 mt-1 text-small">{description}</p>
      <Button
        variant="secondary"
        size="sm"
        className="mt-3"
        pending={refreshing}
        onClick={onRefresh}
      >
        <RefreshCw size={14} /> Làm mới trạng thái
      </Button>
    </div>
  );
}

export function HandoverChecklistPage({ navigate }: { navigate: Navigate }) {
  const appointmentId = pathId(2);
  const [handover, setHandover] = useState<HandoverView | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const [busy, setBusy] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmNotSigned, setConfirmNotSigned] = useState(false);

  const [identityNote, setIdentityNote] = useState("");
  const [inspectionNotes, setInspectionNotes] = useState("");
  const [inspectionPhotos, setInspectionPhotos] = useState<PickedFile[]>([]);
  const [contractScan, setContractScan] = useState<PickedFile[]>([]);
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [overrideDate, setOverrideDate] = useState("");
  const [overrideReason, setOverrideReason] = useState("");
  const [keyQuantity, setKeyQuantity] = useState(1);
  const [docLoading, setDocLoading] = useState(false);
  const [correctionOpen, setCorrectionOpen] = useState(false);
  const [correctionNotes, setCorrectionNotes] = useState("");
  const [correctionPhotos, setCorrectionPhotos] = useState<PickedFile[]>([]);
  const [correctionReason, setCorrectionReason] = useState("");

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        if (appointmentId === null) return;
        let found: HandoverView | null = null;
        const hinted = Number(queryParam("handover"));
        if (Number.isFinite(hinted) && hinted > 0) {
          try {
            const detail = await fetchStaffHandover(hinted);
            if (detail.appointmentId === appointmentId) found = detail;
          } catch {
            // gợi ý sai / không còn quyền — tìm lại trong danh sách
          }
        }
        if (!found) {
          const queue = await fetchStaffHandovers();
          const match = queue.find((item) => item.appointmentId === appointmentId);
          if (match) found = await fetchStaffHandover(match.id);
        }
        if (!active) return;
        setHandover(found);
        if (found) {
          setInspectionNotes(found.inspectionNotes ?? "");
          setInspectionPhotos(filesFromUrls(found.inspectionPhotos));
        }
      } catch (caught) {
        if (active) setError({ status: errorStatus(caught), message: errorMessage(caught) });
      } finally {
        if (active) setIsLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [appointmentId, reloadKey]);

  function reload() {
    setIsLoading(true);
    setError(null);
    setReloadKey((key) => key + 1);
  }

  async function refresh() {
    if (!handover) return;
    setRefreshing(true);
    setActionError(null);
    try {
      setHandover(await fetchStaffHandover(handover.id));
    } catch (caught) {
      setActionError(errorMessage(caught));
    } finally {
      setRefreshing(false);
    }
  }

  async function run(task: () => Promise<HandoverView | null>, success: string) {
    if (!handover) return false;
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const next = await task();
      setHandover(next ?? (await fetchStaffHandover(handover.id)));
      setNotice(success);
      return true;
    } catch (caught) {
      setActionError(errorMessage(caught));
      if (errorStatus(caught) === 409) {
        fetchStaffHandover(handover.id)
          .then(setHandover)
          .catch(() => undefined);
      }
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function openDocument(contractId: number) {
    const popup = window.open("", "_blank");
    setDocLoading(true);
    setActionError(null);
    try {
      const url = await fetchContractDocumentUrl(contractId);
      if (popup) {
        popup.location.href = url;
      } else {
        window.open(url, "_blank");
      }
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (caught) {
      popup?.close();
      setActionError(errorMessage(caught));
    } finally {
      setDocLoading(false);
    }
  }

  if (isLoading) {
    return (
      <div className="grid gap-6">
        <BackLink navigate={navigate} />
        <SurfaceState variant="loading" title="Đang tải phiên bàn giao…" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="grid gap-6">
        <BackLink navigate={navigate} />
        {error.status === 403 ? (
          <SurfaceState
            variant="forbidden"
            title="Bạn không có quyền xem phiên bàn giao này"
            description={error.message}
          />
        ) : (
          <SurfaceState
            variant="error"
            title="Không tải được phiên bàn giao"
            description={error.message}
            action={{ label: "Thử lại", onClick: reload }}
          />
        )}
      </div>
    );
  }

  if (!handover) {
    return (
      <div className="grid gap-6">
        <BackLink navigate={navigate} />
        <SurfaceState
          variant="not-found"
          title="Không có phiên bàn giao đang mở cho lịch này"
          description="Phiên bàn giao chỉ mở sau khi bạn xác nhận khách đã đến. Phiên đã hoàn tất hoặc đã huỷ không còn trong danh sách."
          action={{ label: "Về lịch làm việc", onClick: () => navigate("/staff/schedule") }}
        />
      </div>
    );
  }

  const current = handover;
  const step = current.step;
  const contract = current.contract;
  const invoice = current.firstMonthInvoice;
  const tag = handoverStep(step);
  const activeIndex = stepIndex(step);
  const isClosed = current.result !== "IN_PROGRESS";
  const canCorrectInspection = !isClosed && current.inspectionStatus === "Agreed";
  const photoCount = current.inspectionPhotos?.length ?? 0;
  const inspectionSummary = `${current.inspectionNotes || "Không có ghi chú"} · ${photoCount} ảnh`;

  function openCorrection() {
    setCorrectionNotes(current.inspectionNotes ?? "");
    setCorrectionPhotos(filesFromUrls(current.inspectionPhotos));
    setCorrectionReason("");
    setCorrectionOpen(true);
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <BackLink navigate={navigate} />
          <h1 className="mb-0 mt-3 text-title text-ink">Checklist bàn giao</h1>
          <p className="mt-1.5 text-small text-muted">
            {current.customerName ?? current.customerEmail ?? "—"} · {current.unitCode ?? "—"} · Đơn{" "}
            {current.orderId}
          </p>
        </div>
        <StatusTag tone={tag.tone}>{tag.label}</StatusTag>
      </div>

      {actionError && <DemoNotice tone="error">{actionError}</DemoNotice>}
      {notice && !actionError && <DemoNotice tone="success">{notice}</DemoNotice>}

      <div className="grid grid-cols-[320px_minmax(0,1fr)] items-start gap-6 max-[980px]:grid-cols-1">
        <aside className="grid gap-4">
          <div className="bg-surface p-5">
            <span className="text-label text-muted">Thông tin đơn</span>
            <h2 className="mb-0 mt-1 text-section text-ink">
              {current.customerName ?? current.customerEmail ?? "—"}
            </h2>
            <dl className="mb-0 mt-3 grid gap-2 text-small">
              <Row term="Email">{current.customerEmail ?? "—"}</Row>
              <Row term="Điện thoại">{current.customerPhone ?? "—"}</Row>
              <Row term="Khoang">
                <strong>{current.unitCode ?? "—"}</strong>
              </Row>
              <Row term="Loại khoang">{current.unitType ?? "—"}</Row>
              <Row term="Lịch hẹn">
                {formatTime(current.appointmentStart)}–{formatTime(current.appointmentEnd)}{" "}
                {formatDate(current.appointmentDate)}
              </Row>
              <Row term="Khách đến">{formatDateTime(current.arrivedAt)}</Row>
              <Row term="Hạn bàn giao">{formatDateTime(current.dueAt)}</Row>
              <Row term="Cơ sở">{current.facilityName ?? "—"}</Row>
            </dl>
          </div>

          {!isClosed && (
            <ol className="m-0 grid list-none gap-3 bg-surface p-5">
              {STEP_ORDER.map((item, index) => {
                const isDone = activeIndex > index;
                const isCurrent = activeIndex === index;
                return (
                  <li key={item.key} className="flex items-start gap-3">
                    <span
                      className={`grid h-7 w-7 shrink-0 place-items-center text-label font-bold ${
                        isDone
                          ? "bg-brand text-signal-ink"
                          : isCurrent
                            ? "border-2 border-brand text-ink"
                            : "border border-border text-muted"
                      }`}
                    >
                      {isDone ? <Check size={14} /> : index + 1}
                    </span>
                    <div>
                      <strong
                        className={`block text-small ${isDone || isCurrent ? "text-ink" : "text-muted"}`}
                      >
                        {item.title}
                      </strong>
                      <span className="mt-0.5 block text-label text-muted">
                        {isDone ? "Hoàn tất" : isCurrent ? "Đang thực hiện" : "Chưa làm"}
                      </span>
                    </div>
                  </li>
                );
              })}
            </ol>
          )}
        </aside>

        <section className="bg-surface p-5">
          {isClosed && (
            <ClosedState handover={current} onBack={() => navigate("/staff/handovers")} />
          )}

          {step === "WAITING_ARRIVAL" && (
            <div>
              <h2 className="m-0 text-section text-ink">Khách chưa đến</h2>
              <p className="mt-2 text-small text-muted">
                Xác nhận “Khách đã đến” trên lịch làm việc trước khi bắt đầu bàn giao.
              </p>
              <Button className="mt-4" onClick={() => navigate("/staff/schedule")}>
                Mở lịch làm việc
              </Button>
            </div>
          )}

          {step === "VERIFY_IDENTITY" && (
            <div>
              <span className="text-label text-muted">Bước 01</span>
              <h2 className="mb-0 mt-1 text-section text-ink">Xác minh danh tính</h2>
              <p className="mt-2 text-small leading-relaxed text-muted">
                Đối chiếu CCCD/Passport bản gốc với thông tin khách trên đơn.
              </p>
              {current.identityStatus === "Failed" && (
                <DemoNotice tone="error">
                  Lần xác minh trước không đạt. Có thể xác minh lại khi khách mang đúng giấy tờ.
                </DemoNotice>
              )}
              <div className="mt-4 bg-surface-subtle p-4 text-small">
                <strong className="block text-ink">
                  {current.customerName ?? current.customerEmail ?? "—"}
                </strong>
                <span className="mt-1 block text-muted">
                  {current.customerPhone ?? "—"} · {current.customerEmail ?? "—"}
                </span>
              </div>
              <label className={`${FIELD_LABEL} mt-4`} htmlFor="identity-note">
                Ghi chú (không bắt buộc)
              </label>
              <textarea
                id="identity-note"
                className={TEXTAREA_CLASS}
                value={identityNote}
                maxLength={500}
                onChange={(event) => setIdentityNote(event.target.value)}
                placeholder="Ví dụ: CCCD hết hạn, khách sẽ quay lại."
              />
              <div className="mt-5 flex flex-wrap gap-2">
                <Button
                  pending={busy}
                  onClick={() =>
                    run(
                      () => verifyHandoverIdentity(current.id, true, identityNote.trim()),
                      "Đã xác minh danh tính.",
                    ).then((ok) => ok && setIdentityNote(""))
                  }
                >
                  <Check size={16} /> Xác minh đạt
                </Button>
                <Button
                  variant="secondary"
                  disabled={busy}
                  onClick={() =>
                    run(
                      () => verifyHandoverIdentity(current.id, false, identityNote.trim()),
                      "Đã ghi nhận xác minh không đạt. Phiên bàn giao vẫn mở.",
                    )
                  }
                >
                  Không đạt
                </Button>
              </div>
            </div>
          )}

          {step === "INSPECTION" && (
            <div>
              <span className="text-label text-muted">Bước 02</span>
              <h2 className="mb-0 mt-1 text-section text-ink">Kiểm tra khoang</h2>
              <p className="mt-2 text-small leading-relaxed text-muted">
                Ghi hiện trạng và ảnh chụp. Sửa được cho tới khi khách xác nhận trên tài khoản.
              </p>
              {current.rejectionCount > 0 && (
                <DemoNotice tone="info">
                  Khách đã từ chối {current.rejectionCount}/{current.maxRejectionCount} khoang của
                  đơn này.
                </DemoNotice>
              )}
              <label className={`${FIELD_LABEL} mt-4`} htmlFor="inspection-notes">
                Ghi chú hiện trạng
              </label>
              <textarea
                id="inspection-notes"
                className={TEXTAREA_CLASS}
                value={inspectionNotes}
                maxLength={2000}
                onChange={(event) => setInspectionNotes(event.target.value)}
                placeholder="Ví dụ: khoang sạch, cửa khoá hoạt động, có vết xước nhỏ ở tường trái."
              />
              <div className="mt-3">
                <FileUploadField
                  label="Ảnh hiện trạng (JPEG/PNG/WebP/PDF, ≤ 10MB, tối đa 20)"
                  files={inspectionPhotos}
                  onChange={setInspectionPhotos}
                />
              </div>
              <Button
                className="mt-4"
                pending={busy}
                onClick={async () => {
                  const ok = await run(
                    () =>
                      saveHandoverInspection(
                        current.id,
                        inspectionNotes.trim(),
                        inspectionPhotos.map((photo) => photo.url),
                      ),
                    "Đã lưu hiện trạng khoang.",
                  );
                  if (ok) {
                    await attachUploaded(inspectionPhotos);
                    setInspectionPhotos((list) => list.map((item) => ({ ...item, batchId: null })));
                  }
                }}
              >
                Lưu hiện trạng
              </Button>
              {current.unitInspectedAt === null &&
                (current.inspectionNotes || (current.inspectionPhotos?.length ?? 0) > 0) && (
                  <WaitingCustomer
                    title="Chờ khách xác nhận trên tài khoản"
                    description="Khách xem hiện trạng và bấm đồng ý (hoặc từ chối khoang) trên tài khoản của mình."
                    onRefresh={refresh}
                    refreshing={refreshing}
                  />
                )}
            </div>
          )}

          {(step === "SIGN_CONTRACT" || step === "OVERRIDE_PENDING") && contract && (
            <div>
              <span className="text-label text-muted">Bước 03</span>
              <h2 className="mb-0 mt-1 text-section text-ink">Ký hợp đồng</h2>
              <p className="mt-2 text-small leading-relaxed text-muted">
                In hợp đồng cho khách ký, tải bản scan lên để chuyển sang thanh toán tháng đầu.
              </p>
              <dl className="mb-0 mt-4 grid gap-2 bg-surface-subtle p-4 text-small">
                <Row term="Mã hợp đồng">
                  <strong>{contract.code}</strong>
                </Row>
                <Row term="Trạng thái">{label(contract.status)}</Row>
                <Row term="Giá thuê">{formatMoney(contract.monthlyPrice)} / tháng</Row>
                <Row term="Tiền cọc đã đóng">{formatMoney(contract.depositAmount)}</Row>
                <Row term="Thời hạn">{contract.period} tháng</Row>
                <Row term="Mốc tính tiền">
                  {formatDate(contract.startDate)} – {formatDate(contract.endDate)}
                </Row>
                {contract.startDateOverrideStatus && (
                  <Row term="Đề nghị đổi mốc">
                    {formatDate(contract.startDateOverrideRequested)} ·{" "}
                    {label(contract.startDateOverrideStatus)}
                  </Row>
                )}
                {contract.startDateOverrideReason && (
                  <Row term="Lý do đổi mốc">{contract.startDateOverrideReason}</Row>
                )}
              </dl>

              {step === "OVERRIDE_PENDING" ? (
                <WaitingCustomer
                  title="Chờ FM duyệt mốc tính tiền"
                  description="Bước ký và bản in bị khoá cho tới khi FM duyệt hoặc từ chối đề nghị đổi mốc."
                  onRefresh={refresh}
                  refreshing={refreshing}
                />
              ) : (
                <>
                  <div className="mt-4 flex flex-wrap gap-2">
                    <Button
                      variant="secondary"
                      pending={docLoading}
                      onClick={() => openDocument(contract.id)}
                    >
                      <FileText size={16} /> Xem / in hợp đồng
                    </Button>
                    <Button variant="secondary" onClick={() => setOverrideOpen((open) => !open)}>
                      Đề nghị đổi mốc tính tiền
                    </Button>
                  </div>

                  {overrideOpen && (
                    <div className="mt-4 grid gap-3 bg-surface-subtle p-4">
                      <div>
                        <label className={FIELD_LABEL} htmlFor="override-date">
                          Ngày bắt đầu tính tiền đề nghị
                        </label>
                        <input
                          id="override-date"
                          type="date"
                          min={isoDate(new Date())}
                          className={INPUT_CLASS}
                          value={overrideDate}
                          onChange={(event) => setOverrideDate(event.target.value)}
                        />
                      </div>
                      <div>
                        <label className={FIELD_LABEL} htmlFor="override-reason">
                          Lý do
                        </label>
                        <textarea
                          id="override-reason"
                          className={TEXTAREA_CLASS}
                          value={overrideReason}
                          maxLength={500}
                          onChange={(event) => setOverrideReason(event.target.value)}
                        />
                      </div>
                      <div className="flex flex-wrap gap-2">
                        <Button
                          pending={busy}
                          disabled={!overrideDate || !overrideReason.trim()}
                          onClick={async () => {
                            const ok = await run(async () => {
                              await requestStartDateOverride(
                                contract.id,
                                overrideDate,
                                overrideReason.trim(),
                              );
                              return null;
                            }, "Đã gửi đề nghị đổi mốc tính tiền cho FM.");
                            if (ok) {
                              setOverrideOpen(false);
                              setOverrideDate("");
                              setOverrideReason("");
                            }
                          }}
                        >
                          Gửi đề nghị
                        </Button>
                        <Button variant="secondary" onClick={() => setOverrideOpen(false)}>
                          Đóng
                        </Button>
                      </div>
                    </div>
                  )}

                  <div className="mt-4">
                    <FileUploadField
                      label="Bản scan hợp đồng đã ký (PDF/ảnh, ≤ 10MB)"
                      files={contractScan}
                      onChange={setContractScan}
                      multiple={false}
                    />
                  </div>
                  <Button
                    className="mt-4"
                    pending={busy}
                    disabled={contractScan.length === 0}
                    onClick={async () => {
                      const scan = contractScan[0];
                      if (!scan) return;
                      let invoiceText = "";
                      const ok = await run(async () => {
                        const issued = await signContract(contract.id, scan.url);
                        invoiceText = `${issued.code} · ${formatMoney(issued.amount)}`;
                        return null;
                      }, "Đã ký hợp đồng.");
                      if (ok) {
                        await attachUploaded(contractScan);
                        setContractScan([]);
                        setNotice(`Đã ký hợp đồng, tạo hoá đơn tháng đầu ${invoiceText}.`);
                      }
                    }}
                  >
                    <Check size={16} /> Xác nhận đã ký
                  </Button>
                </>
              )}

              {contract.status === "Draft" && (
                <div className="mt-6 bg-surface-subtle p-4">
                  <strong className="block text-small text-ink">Khách không ký hợp đồng?</strong>
                  <p className="mb-0 mt-1 text-small text-muted">
                    Đơn sẽ bị huỷ theo luồng “Khách không ký hợp đồng”.
                  </p>
                  <Button
                    variant="danger"
                    size="sm"
                    className="mt-3"
                    disabled={busy}
                    onClick={() => setConfirmNotSigned(true)}
                  >
                    Khách không ký
                  </Button>
                </div>
              )}
            </div>
          )}

          {step === "PAY_FIRST_MONTH" && (
            <div>
              <span className="text-label text-muted">Bước 04</span>
              <h2 className="mb-0 mt-1 text-section text-ink">Thanh toán tháng đầu</h2>
              <dl className="mb-0 mt-4 grid gap-2 bg-surface-subtle p-4 text-small">
                <Row term="Hoá đơn">{invoice?.code ?? "—"}</Row>
                <Row term="Số tiền">
                  <strong>{formatMoney(invoice?.amount)}</strong>
                </Row>
                <Row term="Trạng thái">{label(invoice?.status)}</Row>
                <Row term="Hạn thanh toán">{formatDateTime(invoice?.dueDate)}</Row>
              </dl>
              <WaitingCustomer
                title="Chờ khách thanh toán trên tài khoản"
                description="Khách thanh toán hoá đơn tháng đầu qua cổng thanh toán; trạng thái cập nhật khi cổng xác nhận."
                onRefresh={refresh}
                refreshing={refreshing}
              />
            </div>
          )}

          {step === "CUSTOMER_CONFIRM" && (
            <div>
              <span className="text-label text-muted">Bước 05</span>
              <h2 className="mb-0 mt-1 text-section text-ink">Khách xác nhận biên bản</h2>
              <p className="mt-2 text-small leading-relaxed text-muted">
                Hoá đơn tháng đầu đã thanh toán. Khách xác nhận biên bản và chọn hình thức truy cập.
              </p>
              <WaitingCustomer
                title="Chờ khách xác nhận trên tài khoản"
                description="Chọn mã số thì bàn giao chốt ngay; chọn chìa khoá cơ thì bạn giao chìa ở bước kế tiếp."
                onRefresh={refresh}
                refreshing={refreshing}
              />
            </div>
          )}

          {step === "HAND_OVER_KEYS" && (
            <div>
              <span className="text-label text-muted">Bước 06</span>
              <h2 className="mb-0 mt-1 text-section text-ink">Giao chìa khoá</h2>
              <p className="mt-2 text-small leading-relaxed text-muted">
                Khách đã chọn:{" "}
                {ACCESS_LABEL[current.requestedAccess ?? ""] ?? current.requestedAccess}. Ghi số
                chìa giao cho khách để chốt bàn giao.
              </p>
              <label className={`${FIELD_LABEL} mt-4`} htmlFor="key-quantity">
                Số lượng chìa
              </label>
              <input
                id="key-quantity"
                type="number"
                min={1}
                max={10}
                className={`${INPUT_CLASS} max-w-[140px]`}
                value={keyQuantity}
                onChange={(event) => setKeyQuantity(Number(event.target.value))}
              />
              <Button
                className="mt-5"
                pending={busy}
                disabled={!Number.isInteger(keyQuantity) || keyQuantity < 1}
                onClick={() =>
                  run(() => handOverKeys(current.id, keyQuantity), "Bàn giao hoàn tất.")
                }
              >
                <Check size={16} /> Hoàn tất bàn giao
              </Button>
            </div>
          )}

          {canCorrectInspection && (
            <div className="mt-6 bg-surface-subtle p-4">
              <strong className="block text-small text-ink">
                Hiện trạng khoang (khách đã đồng ý)
              </strong>
              <p className="mb-0 mt-1 whitespace-pre-line text-small text-muted">
                {inspectionSummary}
              </p>
              {!correctionOpen ? (
                <Button
                  variant="secondary"
                  size="sm"
                  className="mt-3"
                  disabled={busy}
                  onClick={openCorrection}
                >
                  Sửa hiện trạng
                </Button>
              ) : (
                <div className="mt-3 grid gap-3">
                  <p className="m-0 text-label text-muted">
                    Sửa sau khi khách đã đồng ý cần ghi lý do và được lưu vết. Hợp đồng đã ký giữ
                    nguyên bản đã chốt.
                  </p>
                  <div>
                    <label className={FIELD_LABEL} htmlFor="correction-notes">
                      Ghi chú hiện trạng
                    </label>
                    <textarea
                      id="correction-notes"
                      className={TEXTAREA_CLASS}
                      value={correctionNotes}
                      maxLength={2000}
                      onChange={(event) => setCorrectionNotes(event.target.value)}
                    />
                  </div>
                  <FileUploadField
                    label="Ảnh hiện trạng (JPEG/PNG/WebP/PDF, ≤ 10MB, tối đa 20)"
                    files={correctionPhotos}
                    onChange={setCorrectionPhotos}
                  />
                  <div>
                    <label className={FIELD_LABEL} htmlFor="correction-reason">
                      Lý do sửa
                    </label>
                    <textarea
                      id="correction-reason"
                      className={TEXTAREA_CLASS}
                      value={correctionReason}
                      maxLength={500}
                      onChange={(event) => setCorrectionReason(event.target.value)}
                      placeholder="Ví dụ: bổ sung vết xước khách chỉ ra sau khi đồng ý."
                    />
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      size="sm"
                      pending={busy}
                      disabled={
                        !correctionReason.trim() ||
                        (!correctionNotes.trim() && correctionPhotos.length === 0)
                      }
                      onClick={async () => {
                        const ok = await run(
                          () =>
                            correctHandoverInspection(
                              current.id,
                              correctionNotes.trim(),
                              correctionPhotos.map((photo) => photo.url),
                              correctionReason.trim(),
                            ),
                          "Đã sửa hiện trạng khoang.",
                        );
                        if (ok) {
                          await attachUploaded(correctionPhotos);
                          setCorrectionOpen(false);
                          setCorrectionPhotos([]);
                          setCorrectionReason("");
                        }
                      }}
                    >
                      <Check size={14} /> Lưu hiện trạng đã sửa
                    </Button>
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={busy}
                      onClick={() => setCorrectionOpen(false)}
                    >
                      Đóng
                    </Button>
                  </div>
                </div>
              )}
            </div>
          )}
        </section>
      </div>

      {confirmNotSigned && (
        <ConfirmDialog
          title="Xác nhận khách không ký hợp đồng"
          description="Phiên bàn giao đóng lại và đơn bị huỷ. Thao tác không hoàn tác được."
          confirmLabel="Khách không ký"
          isPending={busy}
          onCancel={() => setConfirmNotSigned(false)}
          onConfirm={async () => {
            await run(
              () => markHandoverNotSigned(current.id),
              "Đã huỷ đơn do khách không ký hợp đồng.",
            );
            setConfirmNotSigned(false);
          }}
        />
      )}
    </div>
  );
}

function ClosedState({ handover, onBack }: { handover: HandoverView; onBack: () => void }) {
  if (handover.result === "COMPLETED") {
    return (
      <div>
        <div className="bg-success-soft p-5 text-success">
          <Check size={26} />
          <h2 className="mb-0 mt-3 text-section">Đã bàn giao khoang {handover.unitCode ?? ""}</h2>
          <p className="mb-0 mt-1 text-small">
            Hoàn tất lúc {formatDateTime(handover.completedAt)}.
          </p>
        </div>
        {handover.accessKeys.length > 0 && (
          <dl className="mb-0 mt-4 grid gap-2 bg-surface-subtle p-4 text-small">
            {handover.accessKeys.map((key) => (
              <Row key={key.id} term={ACCESS_LABEL[key.accessType] ?? key.accessType}>
                {key.quantity !== null ? `${key.quantity} chìa · ` : ""}
                {label(key.status)}
              </Row>
            ))}
          </dl>
        )}
        <Button className="mt-4" onClick={onBack}>
          Về đơn đang bàn giao
        </Button>
      </div>
    );
  }
  return (
    <div>
      <h2 className="m-0 text-section text-ink">
        {handover.result === "REJECTED" ? "Khách từ chối khoang" : "Phiên bàn giao đã huỷ"}
      </h2>
      {handover.rejectReason && (
        <p className="mt-2 text-small text-muted">Lý do: {handover.rejectReason}</p>
      )}
      <Button className="mt-4" onClick={onBack}>
        Về đơn đang bàn giao
      </Button>
    </div>
  );
}
