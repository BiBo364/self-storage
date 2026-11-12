import {
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
  Download,
  FileText,
  KeyRound,
  MapPin,
} from "lucide-react";
import { useCallback, useState, type ReactNode } from "react";
import type { Navigate } from "../app/types";
import { pathId } from "../app/routes";
import { formatDate, formatDateTime, formatMoney, isoDate } from "../app/format";
import { errorMessage } from "../app/http";
import {
  ACCESS_STATUS,
  ACCESS_TYPE_LABEL,
  cancelReturnRequest,
  CHECKOUT_RESULT_LABEL,
  fetchCheckout,
  fetchFacilities,
  fetchMyReturnRequests,
  fetchRental,
  INVOICE_STATUS,
  INVOICE_TYPE_LABEL,
  downloadContractPdf,
  openContractDocument,
  requestExtension,
  requestReturn,
  RETURN_STATUS,
  rotateAccessCode,
  useInvoicePayment,
  useResource,
  type CheckoutView,
  type ContractBill,
  type ReturnRequestView,
} from "../app/services/customer";
import { Button } from "../components/ui/Button";
import { ConfirmDialog } from "../components/ui/ConfirmDialog";
import { DemoNotice } from "../components/ui/DemoNotice";
import { DetailPanel } from "../components/ui/DetailPanel";
import { FIELD_LABEL, INPUT_ELEMENT, INPUT_WRAP } from "../components/ui/FormField";
import { UnitLocationCard } from "../components/domain/UnitLocationCard";
import { SectionTitle } from "../components/ui/SectionTitle";
import { StatusTag } from "../components/ui/StatusTag";
import { SurfaceState } from "../components/ui/SurfaceState";

const CONTRACT_STATUS_LABEL: Record<string, string> = {
  Draft: "Bản nháp",
  Signed: "Đã ký",
  Active: "Đang hiệu lực",
  Ended: "Đã kết thúc",
  Canceled: "Đã huỷ",
};

type Notice = { tone: "success" | "error"; message: string } | null;

export function ContractDetailPage({ navigate }: { navigate: Navigate }) {
  const [contractId] = useState(() => pathId(1));

  if (contractId === null) {
    return (
      <SurfaceState
        variant="not-found"
        title="Không tìm thấy hợp đồng"
        description="Đường dẫn không đúng hoặc hợp đồng không thuộc tài khoản của bạn."
        action={{ label: "Về Kho của tôi", onClick: () => navigate("/my-storage") }}
      />
    );
  }
  return <ContractDetail contractId={contractId} navigate={navigate} />;
}

function ContractDetail({ contractId, navigate }: { contractId: number; navigate: Navigate }) {
  const loadRental = useCallback(() => fetchRental(contractId), [contractId]);
  const rental = useResource(loadRental);
  const returns = useResource(fetchMyReturnRequests);
  const facilities = useResource(fetchFacilities);
  const payment = useInvoicePayment(rental.reload);

  const [notice, setNotice] = useState<Notice>(null);
  const [docPending, setDocPending] = useState(false);
  const [pdfPending, setPdfPending] = useState(false);
  const [codeConfirm, setCodeConfirm] = useState(false);
  const [codePending, setCodePending] = useState(false);
  const [accessCode, setAccessCode] = useState<string | null>(null);
  const [cancelTarget, setCancelTarget] = useState<ReturnRequestView | null>(null);
  const [canceling, setCanceling] = useState(false);
  const [checkoutId, setCheckoutId] = useState<number | null>(null);

  function reloadAll() {
    rental.reload();
    returns.reload();
  }

  async function openDocument() {
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

  async function downloadPdf() {
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

  async function newAccessCode() {
    setCodePending(true);
    setNotice(null);
    try {
      const result = await rotateAccessCode(contractId);
      setAccessCode(result.accessCode);
      rental.reload();
    } catch (caught) {
      setNotice({ tone: "error", message: errorMessage(caught) });
    } finally {
      setCodePending(false);
      setCodeConfirm(false);
    }
  }

  async function confirmCancelReturn() {
    if (!cancelTarget) return;
    setCanceling(true);
    try {
      await cancelReturnRequest(cancelTarget.id);
      setNotice({ tone: "success", message: "Đã huỷ yêu cầu trả kho." });
      reloadAll();
    } catch (caught) {
      setNotice({ tone: "error", message: errorMessage(caught) });
    } finally {
      setCanceling(false);
      setCancelTarget(null);
    }
  }

  if (rental.loading && !rental.data) {
    return (
      <div className="grid gap-3" role="status" aria-label="Đang tải">
        <div className="h-[90px] animate-pulse bg-surface" />
        <div className="h-[72px] animate-pulse bg-surface" />
        <div className="h-[240px] animate-pulse bg-surface" />
      </div>
    );
  }
  if (rental.error && !rental.data) {
    const missing = rental.status === 404 || rental.status === 403;
    return (
      <SurfaceState
        variant={missing ? "not-found" : "error"}
        title={missing ? "Không tìm thấy hợp đồng" : "Không tải được hợp đồng"}
        description={rental.error}
        action={
          missing
            ? { label: "Về Kho của tôi", onClick: () => navigate("/my-storage") }
            : { label: "Thử lại", onClick: rental.reload }
        }
      />
    );
  }
  const contract = rental.data;
  if (!contract) return null;

  const facility = facilities.data?.find((item) => item.id === contract.facilityId) ?? null;
  const pastReturns = (returns.data ?? []).filter(
    (item) => item.contractId === contract.contractId && item.id !== contract.returnRequest?.id,
  );
  const activeKeys = contract.accessKeys.filter((key) => key.status === "Active");

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
            Khoang {contract.unitCode} · Hợp đồng {contract.contractCode}
          </span>
          <h1 className="mt-1 text-title text-ink">{facility?.name ?? "Hợp đồng thuê kho"}</h1>
          {facility?.address && (
            <p className="mt-1.5 flex items-center gap-1.5 text-small text-muted">
              <MapPin size={15} /> {facility.address}
            </p>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <StatusTag
            tone={
              contract.overdue ? "danger" : contract.status === "Active" ? "success" : "neutral"
            }
          >
            {contract.overdue
              ? "Quá hạn"
              : (CONTRACT_STATUS_LABEL[contract.status] ?? contract.status)}
          </StatusTag>
          <Button variant="secondary" pending={docPending} onClick={openDocument}>
            {!docPending && <FileText size={16} />} Xem bản hợp đồng
          </Button>
          <Button variant="secondary" pending={pdfPending} onClick={downloadPdf}>
            {!pdfPending && <Download size={16} />} Tải PDF
          </Button>
        </div>
      </div>

      <UnitLocationCard facilityId={contract.facilityId} unitCode={contract.unitCode} />

      <div className="grid grid-cols-4 gap-2 max-[980px]:grid-cols-2 max-[760px]:grid-cols-1">
        <Summary label="Giá thuê / tháng" value={formatMoney(contract.monthlyPrice)} />
        <Summary label="Tiền cọc" value={formatMoney(contract.depositAmount)} />
        <Summary
          label="Hiệu lực"
          value={`${formatDate(contract.startDate)} → ${formatDate(contract.endDate)}`}
        />
        {contract.status === "Active" ? (
          <Summary
            label={contract.overdue ? "Đã quá hạn" : "Còn lại"}
            value={`${Math.abs(contract.daysLeft)} ngày · kỳ ${contract.period} tháng`}
          />
        ) : (
          // Hợp đồng đã kết thúc/huỷ: số ngày còn lại tính theo end_date cũ không còn ý nghĩa.
          <Summary label="Kỳ thuê" value={`${contract.period} tháng`} />
        )}
      </div>

      {contract.overdue && (
        <DemoNotice tone="error">
          Hợp đồng đã qua ngày kết thúc {formatDate(contract.endDate)}. Phí quá hạn (nếu có) được
          ghi vào hoá đơn của hợp đồng — gia hạn hoặc gửi yêu cầu trả kho để dừng phát sinh.
        </DemoNotice>
      )}
      {notice && <DemoNotice tone={notice.tone}>{notice.message}</DemoNotice>}

      <div className="grid grid-cols-[minmax(0,1fr)_320px] items-start gap-6 max-[980px]:grid-cols-1">
        <div className="grid gap-3">
          <section className="bg-surface p-5">
            <SectionTitle>Hoá đơn của hợp đồng</SectionTitle>
            {payment.payError && <DemoNotice tone="error">{payment.payError.message}</DemoNotice>}
            {contract.invoices.length === 0 ? (
              <p className="mt-3 text-small text-muted">Chưa có hoá đơn nào gắn với hợp đồng.</p>
            ) : (
              <div className="mt-3 grid gap-2">
                {contract.invoices.map((bill) => (
                  <BillRow
                    key={bill.invoiceId}
                    bill={bill}
                    paying={payment.payingId === bill.invoiceId}
                    onPay={() => payment.pay(bill.invoiceId)}
                  />
                ))}
              </div>
            )}
          </section>

          <section className="bg-surface p-5">
            <SectionTitle
              action={
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={contract.status !== "Active"}
                  onClick={() => {
                    setAccessCode(null);
                    setCodeConfirm(true);
                  }}
                >
                  <KeyRound size={14} /> Lấy mã khoá số mới
                </Button>
              }
            >
              Quyền truy cập
            </SectionTitle>
            {accessCode && (
              <div className="mt-4 border-2 border-brand bg-surface-subtle p-4 text-center">
                <span className="text-label text-muted">Mã khoá số mới — chỉ hiển thị một lần</span>
                <strong className="mt-1 block font-mono text-[2.25rem] tracking-[0.3em] text-ink">
                  {accessCode}
                </strong>
                <p className="mt-1 text-label text-muted">
                  Hãy lưu lại mã này. Mã cũ đã bị thu hồi.
                </p>
              </div>
            )}
            {contract.accessKeys.length === 0 ? (
              <p className="mt-3 text-small text-muted">Chưa có quyền truy cập nào được cấp.</p>
            ) : (
              <div className="mt-3 grid gap-2">
                {contract.accessKeys.map((key) => {
                  const status = ACCESS_STATUS[key.status] ?? {
                    label: key.status,
                    tone: "neutral" as const,
                  };
                  return (
                    <div
                      key={key.id}
                      className="flex items-center justify-between gap-3 bg-surface-subtle p-3"
                    >
                      <span className="text-small text-ink">
                        {ACCESS_TYPE_LABEL[key.accessType] ?? key.accessType}
                        {key.accessType === "PhysicalKey" && key.quantity
                          ? ` · ${key.quantity} chìa`
                          : ""}
                      </span>
                      <StatusTag tone={status.tone}>{status.label}</StatusTag>
                    </div>
                  );
                })}
              </div>
            )}
            {activeKeys.length > 0 && (
              <p className="mt-3 text-label text-muted">
                Mã khoá số được lưu dạng mã hoá nên không xem lại được — dùng "Lấy mã khoá số mới"
                khi quên mã.
              </p>
            )}
          </section>

          <section className="bg-surface p-5">
            <SectionTitle>Trả kho</SectionTitle>
            {contract.returnRequest ? (
              <ReturnRow
                request={contract.returnRequest}
                onCancel={() => setCancelTarget(contract.returnRequest)}
                onCheckout={() => setCheckoutId(contract.returnRequest?.id ?? null)}
              />
            ) : (
              <p className="mt-3 text-small text-muted">Chưa có yêu cầu trả kho đang mở.</p>
            )}
            {returns.error && !returns.data && (
              <p className="mt-3 text-label text-danger">
                Không tải được lịch sử trả kho: {returns.error}
              </p>
            )}
            {pastReturns.length > 0 && (
              <div className="mt-4 grid gap-2">
                <span className="text-label text-muted">Yêu cầu trước đây</span>
                {pastReturns.map((item) => (
                  <ReturnRow
                    key={item.id}
                    request={item}
                    onCheckout={() => setCheckoutId(item.id)}
                  />
                ))}
              </div>
            )}
          </section>
        </div>

        <aside className="grid gap-3">
          {contract.canExtend && (
            <ExtendPanel
              contractId={contract.contractId}
              monthlyPrice={contract.monthlyPrice}
              onDone={(bill) => {
                setNotice({
                  tone: "success",
                  message: `Đã tạo hoá đơn gia hạn ${bill.code} · ${formatMoney(bill.amount)}. Thanh toán để cộng thêm kỳ thuê.`,
                });
                rental.reload();
              }}
            />
          )}
          {contract.canRequestReturn && (
            <ReturnPanel
              contractId={contract.contractId}
              onDone={() => {
                setNotice({
                  tone: "success",
                  message: "Đã gửi yêu cầu trả kho. Cơ sở sẽ phân công nhân viên và báo lịch hẹn.",
                });
                reloadAll();
              }}
            />
          )}
          {!contract.canExtend &&
            !contract.canRequestReturn &&
            (contract.invoices.some(
              (bill) => bill.type === "Extension" && bill.status === "Unpaid",
            ) ? (
              <div className="bg-surface-subtle p-4 text-small text-muted">
                Đang có hoá đơn gia hạn chờ thanh toán. Thanh toán hoặc đợi hoá đơn hết hạn rồi mới
                gửi yêu cầu trả kho.
              </div>
            ) : (
              <div className="bg-surface-subtle p-4 text-small text-muted">
                Hợp đồng hiện không có thao tác gia hạn hoặc trả kho.
              </div>
            ))}
          <div className="bg-[var(--dark)] p-4 text-[var(--on-dark-ink)]">
            <h2 className="text-small font-semibold">Cần hỗ trợ?</h2>
            <p className="mt-1.5 text-small text-[rgba(233,234,230,.7)]">
              Báo mất chìa, sự cố khoang hoặc thắc mắc hoá đơn của hợp đồng này.
            </p>
            <Button variant="onDark" className="mt-4" onClick={() => navigate("/support")}>
              Gửi yêu cầu hỗ trợ <ArrowRight size={16} />
            </Button>
          </div>
        </aside>
      </div>

      {codeConfirm && (
        <ConfirmDialog
          title="Lấy mã khoá số mới?"
          description="Mã đang dùng sẽ bị thu hồi ngay và mã mới chỉ hiển thị một lần trên màn hình này."
          confirmLabel="Lấy mã mới"
          tone="default"
          isPending={codePending}
          onConfirm={newAccessCode}
          onCancel={() => setCodeConfirm(false)}
        />
      )}
      {cancelTarget && (
        <ConfirmDialog
          title="Huỷ yêu cầu trả kho?"
          description={`Yêu cầu trả kho ngày ${formatDate(cancelTarget.preferredDate)} và lịch hẹn liên quan (nếu có) sẽ bị huỷ.`}
          confirmLabel="Huỷ yêu cầu"
          cancelLabel="Giữ yêu cầu"
          isPending={canceling}
          onConfirm={confirmCancelReturn}
          onCancel={() => setCancelTarget(null)}
        />
      )}
      {checkoutId !== null && (
        <CheckoutPanel
          returnRequestId={checkoutId}
          onClose={() => setCheckoutId(null)}
          onPay={(invoiceId) => payment.pay(invoiceId)}
          payingId={payment.payingId}
          payError={payment.payError?.message ?? null}
        />
      )}
    </div>
  );
}

function Summary({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-surface p-4">
      <span className="text-label text-muted">{label}</span>
      <strong className="mt-1 block text-data text-ink">{value}</strong>
    </div>
  );
}

function BillRow({
  bill,
  paying,
  onPay,
}: {
  bill: ContractBill;
  paying: boolean;
  onPay: () => void;
}) {
  const status = INVOICE_STATUS[bill.status] ?? { label: bill.status, tone: "neutral" as const };
  return (
    <div className="grid grid-cols-[minmax(0,1fr)_auto_auto] items-center gap-3 bg-surface-subtle p-3 max-[760px]:grid-cols-1">
      <div className="min-w-0">
        <span className="text-label text-muted">
          {bill.code} · {INVOICE_TYPE_LABEL[bill.type] ?? bill.type}
        </span>
        <strong className="block truncate text-small font-semibold text-ink">{bill.title}</strong>
        <span className="text-label text-muted">Hạn {formatDateTime(bill.dueDate)}</span>
      </div>
      <div className="text-right max-[760px]:text-left">
        <strong className="block text-data text-ink">{formatMoney(bill.amount)}</strong>
        <StatusTag tone={status.tone}>{status.label}</StatusTag>
      </div>
      {bill.status === "Unpaid" ? (
        <Button size="sm" pending={paying} onClick={onPay}>
          Thanh toán
        </Button>
      ) : (
        <span />
      )}
    </div>
  );
}

function ReturnRow({
  request,
  onCancel,
  onCheckout,
}: {
  request: ReturnRequestView;
  onCancel?: () => void;
  onCheckout: () => void;
}) {
  const status = RETURN_STATUS[request.status] ?? {
    label: request.status,
    tone: "neutral" as const,
  };
  // Khách huỷ được tới khi FS xác nhận khách đến buổi trả kho (sau đó backend trả 409).
  const open =
    (request.status === "Pending" || request.status === "Assigned") &&
    !request.appointmentArrivedAt;
  return (
    <div className="mt-3 bg-surface-subtle p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <span className="text-label text-muted">Ngày mong muốn</span>
          <strong className="block text-small font-semibold text-ink">
            {formatDate(request.preferredDate)}
          </strong>
          {request.appointmentStart && (
            <span className="mt-1 block text-label text-muted">
              Lịch hẹn: {formatDateTime(request.appointmentStart)}
            </span>
          )}
          {request.note && (
            <span className="mt-1 block text-label text-muted">Ghi chú: {request.note}</span>
          )}
          {request.cancelReason && (
            <span className="mt-1 block text-label text-muted">
              Lý do huỷ: {request.cancelReason}
            </span>
          )}
        </div>
        <StatusTag tone={status.tone}>{status.label}</StatusTag>
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        {request.status !== "Canceled" && request.status !== "Pending" && (
          <Button variant="secondary" size="sm" onClick={onCheckout}>
            Xem biên bản trả kho
          </Button>
        )}
        {open && onCancel && (
          <Button variant="danger" size="sm" onClick={onCancel}>
            Huỷ yêu cầu
          </Button>
        )}
      </div>
    </div>
  );
}

function ExtendPanel({
  contractId,
  monthlyPrice,
  onDone,
}: {
  contractId: number;
  monthlyPrice: number;
  onDone: (bill: ContractBill) => void;
}) {
  const [months, setMonths] = useState("1");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const count = Number(months);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      onDone(await requestExtension(contractId, count));
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="bg-surface p-4" onSubmit={submit}>
      <SectionTitle>Gia hạn hợp đồng</SectionTitle>
      <p className="mt-2 text-label text-muted">
        Hệ thống tạo hoá đơn gia hạn; kỳ thuê chỉ được cộng sau khi thanh toán.
      </p>
      <label className={`${FIELD_LABEL} mt-3`} htmlFor="extend-months">
        Số tháng gia hạn
      </label>
      <div className={INPUT_WRAP}>
        <input
          id="extend-months"
          type="number"
          min={1}
          step={1}
          required
          value={months}
          onChange={(event) => setMonths(event.target.value)}
          className={INPUT_ELEMENT}
        />
        <span className="shrink-0 text-label text-muted">tháng</span>
      </div>
      {count > 0 && (
        <p className="mt-2 text-label text-muted">
          Tạm tính: {formatMoney(Number(monthlyPrice) * count)}
        </p>
      )}
      {error && <DemoNotice tone="error">{error}</DemoNotice>}
      <Button type="submit" className="mt-3 w-full" pending={pending} disabled={!(count > 0)}>
        Tạo hoá đơn gia hạn
      </Button>
    </form>
  );
}

function ReturnPanel({ contractId, onDone }: { contractId: number; onDone: () => void }) {
  const [date, setDate] = useState("");
  const [note, setNote] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await requestReturn(contractId, date, note.trim() || null);
      setDate("");
      setNote("");
      onDone();
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="bg-surface p-4" onSubmit={submit}>
      <SectionTitle>Yêu cầu trả kho</SectionTitle>
      <p className="mt-2 text-label text-muted">
        Chọn ngày bạn muốn trả kho; cơ sở sẽ phân công nhân viên và báo khung giờ.
      </p>
      <label className={`${FIELD_LABEL} mt-3`} htmlFor="return-date">
        Ngày mong muốn
      </label>
      <div className={INPUT_WRAP}>
        <input
          id="return-date"
          type="date"
          required
          min={isoDate(new Date())}
          value={date}
          onChange={(event) => setDate(event.target.value)}
          className={INPUT_ELEMENT}
        />
      </div>
      <label className={`${FIELD_LABEL} mt-3`} htmlFor="return-note">
        Ghi chú (tuỳ chọn)
      </label>
      <textarea
        id="return-note"
        maxLength={1000}
        value={note}
        onChange={(event) => setNote(event.target.value)}
        className="min-h-[64px] w-full rounded-[2px] border border-rule-strong bg-surface p-2.5 text-small text-ink"
      />
      {error && <DemoNotice tone="error">{error}</DemoNotice>}
      <Button
        type="submit"
        variant="secondary"
        className="mt-3 w-full"
        pending={pending}
        disabled={!date}
      >
        Gửi yêu cầu trả kho
      </Button>
    </form>
  );
}

function CheckoutPanel({
  returnRequestId,
  onClose,
  onPay,
  payingId,
  payError,
}: {
  returnRequestId: number;
  onClose: () => void;
  onPay: (invoiceId: number) => void;
  payingId: number | null;
  payError: string | null;
}) {
  const load = useCallback(() => fetchCheckout(returnRequestId), [returnRequestId]);
  const checkout = useResource(load);
  const data: CheckoutView | null = checkout.data;

  let body: ReactNode;
  if (checkout.loading && !data) {
    body = <SurfaceState variant="loading" title="Đang tải biên bản…" />;
  } else if (checkout.error && !data) {
    body = (
      <SurfaceState
        variant="error"
        title="Không tải được biên bản trả kho"
        description={checkout.error}
        action={{ label: "Thử lại", onClick: checkout.reload }}
      />
    );
  } else if (!data || data.result === null) {
    body = (
      <SurfaceState
        variant="empty"
        title="Chưa có biên bản"
        description="Biên bản được lập khi nhân viên kiểm tra khoang trong buổi trả kho."
      />
    );
  } else {
    body = (
      <>
        <dl className="grid gap-2.5">
          <Row label="Kết quả" value={CHECKOUT_RESULT_LABEL[data.result] ?? data.result} />
          <Row label="Khách đến lúc" value={formatDateTime(data.arrivedAt)} />
          <Row label="Kiểm tra lúc" value={formatDateTime(data.inspectedAt)} />
          <Row label="Khoang đã trống" value={data.unitEmptied ? "Có" : "Chưa"} />
          <Row label="Cần vệ sinh" value={data.cleaningRequired ? "Có" : "Không"} />
          <Row
            label="Chìa đã trả"
            value={`${data.returnedKeys ?? 0} / ${data.keysIssued} chìa đã giao`}
          />
          {data.damages && <Row label="Hư hỏng" value={data.damages} />}
          {data.note && <Row label="Ghi chú" value={data.note} />}
        </dl>
        <h3 className="mt-6 text-small font-semibold text-ink">Quyết toán</h3>
        <dl className="mt-2.5 grid gap-2.5">
          <Row label="Tiền cọc" value={formatMoney(data.depositAmount)} />
          <Row
            label="Phí vệ sinh"
            value={formatMoney(data.cleaningRequired ? data.cleaningFee : 0)}
          />
          <Row label="Phí hư hỏng" value={formatMoney(data.damageAmount)} />
          <Row label="Phí mất chìa (mỗi chìa)" value={formatMoney(data.lostKeyFee)} />
          <Row
            label="Hoá đơn chưa trả của hợp đồng"
            value={formatMoney(data.unpaidContractInvoices)}
          />
          <Row label="Tổng phí" value={formatMoney(data.totalCharges)} />
          <Row label="Tiền hoàn lại" value={formatMoney(data.refundAmount)} />
          <Row label="Hoàn tất lúc" value={formatDateTime(data.completedAt)} />
        </dl>
        {data.photos.length > 0 && (
          <div className="mt-5 grid grid-cols-3 gap-2">
            {data.photos.map((url) => (
              <a key={url} href={url} target="_blank" rel="noreferrer">
                <img src={url} alt="Ảnh biên bản trả kho" className="h-20 w-full object-cover" />
              </a>
            ))}
          </div>
        )}
        {data.shortfallInvoice && (
          <div className="mt-5 bg-warning-soft p-4 text-warning">
            <p className="flex items-center gap-2 text-small font-semibold">
              <AlertTriangle size={16} /> Phần phí vượt tiền cọc:{" "}
              {formatMoney(data.shortfallInvoice.amount)}
            </p>
            <p className="mt-1 text-label">
              {data.shortfallInvoice.code} ·{" "}
              {INVOICE_STATUS[data.shortfallInvoice.status]?.label ?? data.shortfallInvoice.status}
            </p>
            {payError && <DemoNotice tone="error">{payError}</DemoNotice>}
            {data.shortfallInvoice.status === "Unpaid" && (
              <Button
                className="mt-3"
                size="sm"
                pending={payingId === data.shortfallInvoice.invoiceId}
                onClick={() => data.shortfallInvoice && onPay(data.shortfallInvoice.invoiceId)}
              >
                Thanh toán
              </Button>
            )}
          </div>
        )}
      </>
    );
  }

  return (
    <DetailPanel title="Biên bản trả kho" eyebrow={`Yêu cầu #${returnRequestId}`} onClose={onClose}>
      {body}
    </DetailPanel>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start justify-between gap-4 text-small">
      <dt className="text-muted">{label}</dt>
      <dd className="m-0 text-right font-semibold text-ink">{value}</dd>
    </div>
  );
}
