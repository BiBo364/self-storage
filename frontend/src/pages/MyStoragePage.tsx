import { AlertTriangle, ArrowRight, Plus } from "lucide-react";
import type { Navigate } from "../app/types";
import { formatPrice } from "../mocks/catalog";
import { rentedStorage, type StorageStatus } from "../mocks/customer";
import { proposals } from "../mocks/proposals";
import { Button } from "../components/ui/Button";
import { StatusTag } from "../components/ui/StatusTag";

const STATUS: Record<StorageStatus, { label: string; tone: "success" | "warning" | "danger" }> = {
  active: { label: "Đang thuê", tone: "success" },
  expiring: { label: "Sắp đến hạn", tone: "warning" },
  overdue: { label: "Đã quá hạn", tone: "danger" },
};

export function MyStoragePage({ navigate }: { navigate: Navigate }) {
  const pendingProposalCount = proposals.filter((proposal) => proposal.status === "pending").length;
  const expiring = rentedStorage.filter((storage) => storage.status !== "active");
  const rentalCount = rentedStorage.length;
  const firstExpiring = expiring[0];
  const taskCount = (pendingProposalCount > 0 ? 1 : 0) + (firstExpiring ? 1 : 0);

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Kho của tôi</h1>
          <p className="mt-1.5 text-small text-muted">
            Theo dõi hợp đồng, thời hạn và việc cần làm tiếp theo.
          </p>
        </div>
        <Button onClick={() => navigate("/rental-requests/new")}>
          Thuê thêm kho <Plus size={16} />
        </Button>
      </div>

      <div className="grid grid-cols-3 gap-2 max-[760px]:grid-cols-1">
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">
            {String(rentalCount).padStart(2, "0")}
          </strong>
          <span className="text-label text-muted">Khoang đang thuê</span>
        </div>
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">
            {String(pendingProposalCount).padStart(2, "0")}
          </strong>
          <span className="text-label text-muted">Đề xuất chờ xác nhận</span>
        </div>
        <div className="bg-surface p-4">
          <strong className="block text-stat text-ink">
            {String(expiring.length).padStart(2, "0")}
          </strong>
          <span className="text-label text-muted">Khoang cần chú ý</span>
        </div>
      </div>

      <div className="grid grid-cols-[minmax(0,1fr)_312px] items-start gap-8 max-[1100px]:grid-cols-1">
        <section>
          <div className="mb-2 flex items-center justify-between gap-4">
            <h2 className="flex items-center gap-2 text-[15px] font-semibold text-ink">
              <span className="h-2 w-2 bg-brand" aria-hidden="true" />
              Hợp đồng đang hoạt động
            </h2>
            <span className="text-label text-muted">{rentedStorage.length} hợp đồng</span>
          </div>
          <div className="grid gap-3">
            {rentedStorage.map((storage) => {
              const status = STATUS[storage.status];
              return (
                <article key={storage.id} className="border border-border bg-surface p-5">
                  <div className="flex items-start justify-between gap-4">
                    <div className="min-w-0">
                      <div className="flex items-baseline gap-2">
                        <strong className="whitespace-nowrap text-stat text-ink">
                          {storage.unitCode}
                        </strong>
                        <span className="text-label text-muted">{storage.size}</span>
                      </div>
                      <strong className="mt-1 block truncate text-small font-semibold text-ink">
                        {storage.facility}
                      </strong>
                      <span className="mt-0.5 block truncate text-label text-muted">
                        {storage.district}
                      </span>
                    </div>
                    <div className="flex shrink-0 flex-col items-end gap-2">
                      <StatusTag tone={status.tone}>{status.label}</StatusTag>
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => navigate(`/my-storage/${storage.id}`)}
                      >
                        Xem
                      </Button>
                    </div>
                  </div>
                  <dl className="mt-4 grid grid-cols-2 gap-4 border-t border-border pt-4 max-[760px]:grid-cols-1">
                    <div>
                      <dt className="text-label text-muted">Thời hạn</dt>
                      <dd className="m-0 mt-1 whitespace-nowrap text-small text-ink">
                        {storage.startDate} → {storage.endDate}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-label text-muted">Giá thuê</dt>
                      <dd className="m-0 mt-1 text-small font-semibold text-ink">
                        {formatPrice(storage.monthlyPrice)} đ / tháng
                      </dd>
                    </div>
                  </dl>
                  {/* Chỉ hiện khi sắp đến hạn / quá hạn. TẠM: mock dùng status; API thật trả is_expiring_soon / is_overdue / has_unpaid_invoice. */}
                  {storage.status !== "active" && (
                    <div
                      className={`mt-4 flex items-center justify-between gap-4 px-3 py-2.5 ${
                        storage.status === "overdue"
                          ? "bg-danger-soft text-danger"
                          : "bg-warning-soft text-warning"
                      }`}
                    >
                      <span className="flex items-center gap-2 text-small font-semibold">
                        <AlertTriangle size={16} /> {storage.paymentLabel}
                      </span>
                      <button
                        className="inline-flex shrink-0 items-center gap-1 border-0 bg-transparent p-0 text-label font-bold hover:underline"
                        onClick={() => navigate("/invoices")}
                      >
                        Thanh toán <ArrowRight size={14} />
                      </button>
                    </div>
                  )}
                </article>
              );
            })}
          </div>
        </section>

        <aside className="grid gap-4">
          <div className="bg-surface-subtle p-4">
            <div className="mb-3 flex items-center justify-between gap-3">
              <h2 className="flex items-center gap-2 text-small font-semibold text-ink">
                <span className="h-2 w-2 bg-brand" aria-hidden="true" />
                Việc cần làm
              </h2>
              <span className="text-label text-muted">{String(taskCount).padStart(2, "0")}</span>
            </div>
            <div className="grid gap-2">
              {pendingProposalCount > 0 && (
                <button
                  className="flex items-start justify-between gap-3 bg-surface p-3 text-left"
                  onClick={() => navigate("/proposals")}
                >
                  <span>
                    <strong className="block text-small font-semibold text-ink">
                      Xác nhận đề xuất khoang
                    </strong>
                    <span className="mt-0.5 block text-label text-muted">
                      {pendingProposalCount} đề xuất đang chờ phản hồi.
                    </span>
                  </span>
                  <ArrowRight size={16} className="mt-0.5 shrink-0 text-muted" />
                </button>
              )}
              {firstExpiring && (
                <button
                  className="flex items-start justify-between gap-3 bg-surface p-3 text-left"
                  onClick={() => navigate("/invoices")}
                >
                  <span>
                    <strong className="block text-small font-semibold text-ink">
                      Thanh toán khoang {firstExpiring.unitCode}
                    </strong>
                    <span className="mt-0.5 block text-label text-muted">
                      {firstExpiring.paymentLabel}
                    </span>
                  </span>
                  <ArrowRight size={16} className="mt-0.5 shrink-0 text-muted" />
                </button>
              )}
            </div>
          </div>

          <div className="bg-[var(--dark)] p-4 text-[var(--on-dark-ink)]">
            <h2 className="text-small font-semibold">Hỗ trợ nhanh</h2>
            <p className="mt-1.5 text-small text-[rgba(233,234,230,.7)]">
              Báo sự cố khoang, khoá truy cập hoặc yêu cầu hỗ trợ tại cơ sở.
            </p>
            <a
              className="mt-4 inline-flex min-h-[38px] items-center gap-2 border-2 border-on-dark-border px-3 text-small font-semibold text-on-dark-ink hover:border-on-dark-border-hover hover:bg-on-dark-bg-hover"
              href="mailto:hello@khomoc.example"
            >
              Gửi yêu cầu hỗ trợ <ArrowRight size={16} />
            </a>
          </div>
        </aside>
      </div>
    </div>
  );
}
