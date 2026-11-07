import { AlertTriangle, ArrowRight, Check, Clock3, RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import type { Navigate } from "../app/types";
import { queryParam } from "../app/routes";
import { errorMessage } from "../app/http";
import {
  checkInvoicePayment,
  clearPendingInvoiceId,
  INVOICE_STATUS,
  readPendingInvoiceId,
  type PaymentCheck,
} from "../app/services/customer";
import { Button } from "../components/ui/Button";
import { SurfaceState } from "../components/ui/SurfaceState";

const POLL_INTERVAL_MS = 3000;
const MAX_AUTO_CHECKS = 5;

type Outcome = "success" | "failed" | "pending";

function outcomeOf(check: PaymentCheck): Outcome {
  if (check.invoiceStatus === "Paid") return "success";
  if (
    check.paymentStatus === "Failed" ||
    check.invoiceStatus === "Canceled" ||
    check.invoiceStatus === "Expired"
  ) {
    return "failed";
  }
  return "pending";
}

function initialInvoiceId(): number | null {
  const fromQuery = Number(queryParam("invoiceId"));
  if (Number.isFinite(fromQuery) && fromQuery > 0) return fromQuery;
  return readPendingInvoiceId();
}

/** Kết quả thanh toán sau khi quay về từ cổng payOS. */
export function PaymentResultPage({ navigate }: { navigate: Navigate }) {
  const [invoiceId] = useState(initialInvoiceId);
  const [gatewayCanceled] = useState(
    () => queryParam("cancel") === "true" || queryParam("status") === "CANCELLED",
  );
  const [attempt, setAttempt] = useState(1);
  const [result, setResult] = useState<{
    attempt: number;
    check: PaymentCheck | null;
    error: string | null;
  } | null>(null);

  useEffect(() => {
    if (invoiceId === null) return;
    let active = true;
    checkInvoicePayment(invoiceId).then(
      (check) => {
        if (active) setResult({ attempt, check, error: null });
      },
      (caught: unknown) => {
        if (active) {
          setResult((previous) => ({
            attempt,
            check: previous?.check ?? null,
            error: errorMessage(caught),
          }));
        }
      },
    );
    return () => {
      active = false;
    };
  }, [invoiceId, attempt]);

  const checking = result === null || result.attempt !== attempt;
  const check = result?.check ?? null;
  const outcome: Outcome | null = check ? outcomeOf(check) : null;

  // Còn Pending → tự kiểm tra lại vài lần.
  useEffect(() => {
    if (checking || outcome !== "pending" || attempt >= MAX_AUTO_CHECKS) return;
    const timer = window.setTimeout(() => setAttempt((value) => value + 1), POLL_INTERVAL_MS);
    return () => window.clearTimeout(timer);
  }, [checking, outcome, attempt]);

  useEffect(() => {
    if (outcome === "success" || outcome === "failed") clearPendingInvoiceId();
  }, [outcome]);

  if (invoiceId === null) {
    return (
      <SurfaceState
        variant="not-found"
        title="Không xác định được hoá đơn"
        description="Không tìm thấy hoá đơn vừa thanh toán trong phiên này. Hãy kiểm tra trạng thái trong danh sách hoá đơn."
        action={{ label: "Đến trang hoá đơn", onClick: () => navigate("/invoices") }}
      />
    );
  }

  const links = (
    <div className="mt-6 flex flex-wrap justify-center gap-2.5">
      <Button
        variant={outcome === "success" ? "primary" : "secondary"}
        onClick={() => navigate("/my-storage")}
      >
        Về Kho của tôi <ArrowRight size={16} />
      </Button>
      <Button variant="secondary" onClick={() => navigate("/invoices")}>
        Xem hoá đơn
      </Button>
    </div>
  );

  if (!check) {
    if (checking) {
      return (
        <SurfaceState
          variant="loading"
          title="Đang kiểm tra kết quả thanh toán…"
          description={`Hoá đơn #${invoiceId}`}
        />
      );
    }
    return (
      <div className="grid gap-4">
        <SurfaceState
          variant="error"
          title="Không kiểm tra được kết quả thanh toán"
          description={result?.error ?? undefined}
          action={{ label: "Kiểm tra lại", onClick: () => setAttempt((value) => value + 1) }}
        />
        {links}
      </div>
    );
  }

  const invoiceLabel = INVOICE_STATUS[check.invoiceStatus]?.label ?? check.invoiceStatus;

  return (
    <div className="mx-auto grid w-full max-w-[620px] gap-4">
      <div className="bg-surface px-6 py-10 text-center" role="status">
        <span
          className={`inline-grid h-12 w-12 place-items-center ${
            outcome === "success"
              ? "bg-brand text-signal-ink"
              : outcome === "failed"
                ? "bg-danger-soft text-danger"
                : "bg-warning-soft text-warning"
          }`}
        >
          {outcome === "success" ? (
            <Check size={24} aria-hidden="true" />
          ) : outcome === "failed" ? (
            <AlertTriangle size={24} aria-hidden="true" />
          ) : (
            <Clock3 size={24} aria-hidden="true" />
          )}
        </span>
        <h1 className="mx-auto mt-4 max-w-[22ch] text-title text-ink">
          {outcome === "success"
            ? "Thanh toán thành công."
            : outcome === "failed"
              ? "Thanh toán chưa thành công."
              : "Đang chờ cổng thanh toán xác nhận."}
        </h1>
        <p className="mx-auto mt-2 max-w-[430px] text-small leading-relaxed text-muted">
          Hoá đơn #{check.invoiceId} · {invoiceLabel}
        </p>
        {outcome === "failed" && check.failureReason && (
          <p className="mx-auto mt-2 max-w-[430px] text-small text-danger">{check.failureReason}</p>
        )}
        {outcome === "pending" && (
          <p className="mx-auto mt-2 max-w-[430px] text-small leading-relaxed text-muted">
            {gatewayCanceled
              ? "Cổng thanh toán báo bạn đã huỷ giao dịch. Hệ thống đang đối soát lại trạng thái."
              : "Giao dịch chưa được xác nhận. Trang sẽ tự kiểm tra lại sau vài giây."}
          </p>
        )}
        {result?.error && (
          <p className="mx-auto mt-2 max-w-[430px] text-small text-danger" role="alert">
            {result.error}
          </p>
        )}
        {outcome !== "success" && (
          <Button
            variant="secondary"
            className="mt-5"
            pending={checking}
            onClick={() => setAttempt((value) => value + 1)}
          >
            {!checking && <RefreshCw size={16} />} Kiểm tra lại
          </Button>
        )}
        {links}
      </div>
    </div>
  );
}
