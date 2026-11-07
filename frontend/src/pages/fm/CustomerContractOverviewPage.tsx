import { ArrowRight, Check, FileText, Search, X } from "lucide-react";
import { useState } from "react";
import { formatDate, formatMoney } from "../../app/format";
import {
  CONTRACT_STATUS_LABEL,
  CONTRACT_STATUS_TONE,
  fetchFacilityContracts,
  fetchStartDateOverrides,
  labelOf,
  loadErrorState,
  openContractDocument,
  reviewStartDateOverride,
  toneOf,
  useFmAction,
  useFmLoad,
  type StartDateOverride,
} from "../../app/services/fm";
import type { Navigate } from "../../app/types";
import { Button } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { FilterSelect } from "../../components/ui/FilterSelect";
import { INPUT_ELEMENT, INPUT_WRAP } from "../../components/ui/FormField";
import { SectionTitle } from "../../components/ui/SectionTitle";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";

const TABLE_COLUMNS = "grid-cols-[1.2fr_1.2fr_0.6fr_1fr_0.8fr_0.8fr_0.8fr_110px]";

export function CustomerContractOverviewPage({ navigate }: { navigate: Navigate }) {
  const [status, setStatus] = useState("");
  const [search, setSearch] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const documentAction = useFmAction();

  const contracts = useFmLoad(`contracts:${status}`, (facility) =>
    fetchFacilityContracts(facility.id, status || undefined),
  );
  const overrides = useFmLoad("start-date-overrides", () => fetchStartDateOverrides());

  const term = search.trim().toLowerCase();
  const list = (contracts.data ?? []).filter((contract) => {
    if (!term) return true;
    return [
      contract.code,
      contract.customerName,
      contract.customerEmail,
      contract.customerPhone,
      contract.unitCode,
    ]
      .map((value) => String(value ?? "").toLowerCase())
      .some((value) => value.includes(term));
  });

  function openDocument(contractId: number) {
    documentAction.run(`doc-${contractId}`, () => openContractDocument(contractId));
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Hợp đồng khách hàng</h1>
          <p className="mt-1.5 text-small text-muted">
            Hợp đồng của {contracts.facility ? `cơ sở ${contracts.facility.name}` : "cơ sở"} kèm
            tổng hoá đơn chưa thanh toán.
          </p>
        </div>
        <Button variant="secondary" onClick={() => navigate("/fm/extend-requests")}>
          Hợp đồng quá hạn <ArrowRight size={14} />
        </Button>
      </div>

      <section className="grid gap-3">
        <SectionTitle>Đề nghị đổi mốc tính tiền chờ duyệt</SectionTitle>
        {overrides.error ? (
          <SurfaceState
            {...loadErrorState(overrides.error, "đề nghị đổi mốc tính tiền")}
            action={{ label: "Thử lại", onClick: overrides.reload }}
          />
        ) : !overrides.data ? (
          <SurfaceState variant="loading" title="Đang tải đề nghị…" />
        ) : overrides.data.length === 0 ? (
          <div className="bg-surface p-4 text-small text-muted">
            Không có đề nghị đổi mốc tính tiền nào đang chờ.
          </div>
        ) : (
          <div className="grid gap-2">
            {overrides.data.map((item) => (
              <OverrideRow
                key={item.contractId}
                item={item}
                onReviewed={(message) => {
                  setNotice(message);
                  overrides.reload();
                  contracts.reload();
                }}
              />
            ))}
          </div>
        )}
      </section>

      {notice && <DemoNotice tone="success">{notice}</DemoNotice>}

      <section className="grid gap-3">
        <SectionTitle>Danh sách hợp đồng</SectionTitle>
        <div className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,0.8fr)] items-end gap-3 max-[760px]:grid-cols-1">
          <label className="grid gap-1.5">
            <span className="text-label text-muted">Tìm kiếm</span>
            <div className={INPUT_WRAP}>
              <Search size={15} />
              <input
                className={INPUT_ELEMENT}
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Mã hợp đồng, khách, số điện thoại, khoang…"
              />
            </div>
          </label>
          <FilterSelect label="Trạng thái" value={status} onChange={setStatus}>
            <option value="">Tất cả trạng thái</option>
            {Object.keys(CONTRACT_STATUS_LABEL).map((value) => (
              <option key={value} value={value}>
                {CONTRACT_STATUS_LABEL[value]}
              </option>
            ))}
          </FilterSelect>
        </div>

        {documentAction.error && (
          <DemoNotice tone="error">Không mở được bản hợp đồng: {documentAction.error}</DemoNotice>
        )}

        {contracts.error ? (
          <SurfaceState
            {...loadErrorState(contracts.error, "hợp đồng")}
            action={{ label: "Thử lại", onClick: contracts.reload }}
          />
        ) : !contracts.data ? (
          <SurfaceState variant="loading" title="Đang tải hợp đồng…" />
        ) : list.length === 0 ? (
          <SurfaceState
            variant="empty"
            title="Không có hợp đồng phù hợp"
            description="Thử đổi trạng thái hoặc từ khoá tìm kiếm."
          />
        ) : (
          <div className="overflow-x-auto" aria-busy={contracts.loading}>
            <div className={`min-w-[1040px] ${contracts.loading ? "opacity-60" : ""}`}>
              <div
                className={`grid ${TABLE_COLUMNS} gap-4 bg-[var(--dark)] px-3.5 py-2.5 text-label text-[rgba(233,234,230,.62)]`}
              >
                <span>Hợp đồng</span>
                <span>Khách hàng</span>
                <span>Khoang</span>
                <span>Thời hạn</span>
                <span className="text-right">Giá / tháng</span>
                <span className="text-right">Chưa thanh toán</span>
                <span>Trạng thái</span>
                <span className="text-right">Bản hợp đồng</span>
              </div>
              <div className="mt-1.5 grid gap-1.5">
                {list.map((contract) => (
                  <div
                    key={contract.id}
                    className={`grid ${TABLE_COLUMNS} items-center gap-4 bg-surface px-3.5 py-3`}
                  >
                    <div className="min-w-0">
                      <strong className="block truncate text-small font-semibold text-ink">
                        {contract.code}
                      </strong>
                      <span className="mt-0.5 block text-label text-muted">
                        Đơn #{contract.orderId}
                        {contract.signedAt ? ` · ký ${formatDate(contract.signedAt)}` : ""}
                      </span>
                    </div>
                    <div className="min-w-0">
                      <span className="block truncate text-small text-ink">
                        {contract.customerName ?? contract.customerEmail ?? "—"}
                      </span>
                      <span className="mt-0.5 block truncate text-label text-muted">
                        {[contract.customerPhone, contract.customerEmail]
                          .filter(Boolean)
                          .join(" · ") || "—"}
                      </span>
                    </div>
                    <span className="text-small text-ink">{contract.unitCode ?? "—"}</span>
                    <span className="text-label text-ink tabular-nums">
                      {formatDate(contract.startDate)} – {formatDate(contract.endDate)}
                      <span className="mt-0.5 block text-muted">{contract.period} tháng</span>
                    </span>
                    <span className="text-right text-small text-ink tabular-nums">
                      {formatMoney(contract.monthlyPrice)}
                    </span>
                    <span
                      className={`text-right text-small font-semibold tabular-nums ${
                        Number(contract.unpaidAmount ?? 0) > 0 ? "text-danger" : "text-ink"
                      }`}
                    >
                      {formatMoney(contract.unpaidAmount ?? 0)}
                    </span>
                    <StatusTag tone={toneOf(CONTRACT_STATUS_TONE, contract.status)}>
                      {labelOf(CONTRACT_STATUS_LABEL, contract.status)}
                    </StatusTag>
                    <div className="flex justify-end">
                      <Button
                        variant="secondary"
                        size="sm"
                        pending={documentAction.pending === `doc-${contract.id}`}
                        onClick={() => openDocument(contract.id)}
                      >
                        {documentAction.pending !== `doc-${contract.id}` && <FileText size={14} />}
                        Xem
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
              <p className="mt-3 text-label text-muted">{list.length} hợp đồng</p>
            </div>
          </div>
        )}
      </section>
    </div>
  );
}

function OverrideRow({
  item,
  onReviewed,
}: {
  item: StartDateOverride;
  onReviewed: (message: string) => void;
}) {
  const [reason, setReason] = useState("");
  const action = useFmAction();
  const reasonId = `override-reason-${item.contractId}`;

  async function review(decision: "approve" | "reject") {
    const outcome = await action.run(decision, () =>
      reviewStartDateOverride(item.contractId, decision, reason),
    );
    if (outcome.ok) {
      onReviewed(
        decision === "approve"
          ? `Đã duyệt mốc tính tiền ${formatDate(item.requestedDate)} cho hợp đồng ${item.contractCode}.`
          : `Đã từ chối đổi mốc tính tiền của hợp đồng ${item.contractCode}.`,
      );
    }
  }

  return (
    <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)] gap-4 bg-surface p-4 max-[900px]:grid-cols-1">
      <div className="min-w-0">
        <strong className="block text-small font-semibold text-ink">{item.contractCode}</strong>
        <span className="mt-0.5 block text-label text-muted">
          {item.customerName ?? "—"} · Khoang {item.unitCode ?? "—"} · Đơn #{item.orderId}
        </span>
        <p className="mt-2 text-small text-ink tabular-nums">
          Mốc theo chính sách {formatDate(item.startDate)} → đề nghị{" "}
          <strong>{formatDate(item.requestedDate)}</strong>
        </p>
        {item.reason && <p className="mt-1 text-label text-muted">Lý do của FS: {item.reason}</p>}
      </div>
      <div>
        <label className="text-label text-muted" htmlFor={reasonId}>
          Ghi chú duyệt (tuỳ chọn)
        </label>
        <div className={`${INPUT_WRAP} mt-1.5`}>
          <input
            id={reasonId}
            className={INPUT_ELEMENT}
            value={reason}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
        </div>
        <div className="mt-2 flex flex-wrap gap-2">
          <Button
            size="sm"
            pending={action.pending === "approve"}
            disabled={action.pending !== null}
            onClick={() => review("approve")}
          >
            {action.pending !== "approve" && <Check size={14} />} Duyệt mốc mới
          </Button>
          <Button
            size="sm"
            variant="danger"
            pending={action.pending === "reject"}
            disabled={action.pending !== null}
            onClick={() => review("reject")}
          >
            {action.pending !== "reject" && <X size={14} />} Giữ mốc chính sách
          </Button>
        </div>
        {action.error && <DemoNotice tone="error">{action.error}</DemoNotice>}
      </div>
    </div>
  );
}
