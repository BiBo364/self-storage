import { ImagePlus, Plus, X } from "lucide-react";
import { useState, type FormEvent } from "react";
import type { Navigate } from "../app/types";
import { formatDateTime, formatMoney } from "../app/format";
import { errorMessage } from "../app/http";
import { attachMediaBatch, uploadMedia } from "../app/media";
import {
  cancelSupportRequest,
  createSupportRequest,
  fetchFacilities,
  fetchRentals,
  fetchSupportRequests,
  INVOICE_STATUS,
  SUPPORT_CATEGORY_LABEL,
  SUPPORT_STATUS,
  useInvoicePayment,
  useResource,
  type SupportCategory,
  type SupportView,
} from "../app/services/customer";
import { Button } from "../components/ui/Button";
import { ConfirmDialog } from "../components/ui/ConfirmDialog";
import { DemoNotice } from "../components/ui/DemoNotice";
import { FIELD_LABEL, INPUT_ELEMENT, INPUT_WRAP } from "../components/ui/FormField";
import { SectionTitle } from "../components/ui/SectionTitle";
import { StatusTag } from "../components/ui/StatusTag";
import { SurfaceState } from "../components/ui/SurfaceState";

const MAX_PHOTOS = 10;
const CATEGORIES = Object.keys(SUPPORT_CATEGORY_LABEL) as SupportCategory[];

/** Khách — yêu cầu hỗ trợ / sự cố. */
export function SupportPage({ navigate }: { navigate: Navigate }) {
  const requests = useResource(fetchSupportRequests);
  const payment = useInvoicePayment(requests.reload);
  const [formOpen, setFormOpen] = useState(false);
  const [notice, setNotice] = useState<{ tone: "success" | "error"; message: string } | null>(null);
  const [cancelTarget, setCancelTarget] = useState<SupportView | null>(null);
  const [canceling, setCanceling] = useState(false);

  const list = requests.data ?? [];

  async function confirmCancel() {
    if (!cancelTarget) return;
    setCanceling(true);
    try {
      await cancelSupportRequest(cancelTarget.id);
      setNotice({ tone: "success", message: `Đã huỷ yêu cầu "${cancelTarget.title}".` });
    } catch (caught) {
      setNotice({ tone: "error", message: errorMessage(caught) });
    } finally {
      setCanceling(false);
      setCancelTarget(null);
      requests.reload();
    }
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="m-0 text-title text-ink">Hỗ trợ</h1>
          <p className="mt-1.5 text-small text-muted">
            Báo mất chìa, sự cố khoang, mã khoá số hoặc thắc mắc hoá đơn. Cơ sở sẽ phân công nhân
            viên xử lý.
          </p>
        </div>
        {!formOpen && (
          <Button
            onClick={() => {
              setFormOpen(true);
              setNotice(null);
            }}
          >
            Gửi yêu cầu mới <Plus size={16} />
          </Button>
        )}
      </div>

      {notice && <DemoNotice tone={notice.tone}>{notice.message}</DemoNotice>}

      {formOpen && (
        <CreateForm
          onCancel={() => setFormOpen(false)}
          onCreated={(created) => {
            setFormOpen(false);
            setNotice({ tone: "success", message: `Đã gửi yêu cầu "${created.title}".` });
            requests.reload();
          }}
        />
      )}

      <section>
        <div className="mb-2">
          <SectionTitle
            action={
              requests.data ? (
                <span className="text-label text-muted">{list.length} yêu cầu</span>
              ) : undefined
            }
          >
            Yêu cầu của tôi
          </SectionTitle>
        </div>
        {payment.payError && <DemoNotice tone="error">{payment.payError.message}</DemoNotice>}
        {requests.loading && !requests.data ? (
          <div className="grid gap-2" role="status" aria-label="Đang tải">
            {[0, 1].map((index) => (
              <div key={index} className="h-[120px] animate-pulse bg-surface" />
            ))}
          </div>
        ) : requests.error && !requests.data ? (
          <SurfaceState
            variant="error"
            title="Không tải được yêu cầu hỗ trợ"
            description={requests.error}
            action={{ label: "Thử lại", onClick: requests.reload }}
          />
        ) : list.length === 0 ? (
          <SurfaceState
            variant="empty"
            title="Chưa có yêu cầu hỗ trợ nào"
            description="Khi gặp vấn đề với khoang đang thuê, gửi yêu cầu để cơ sở hỗ trợ."
            action={
              formOpen ? undefined : { label: "Gửi yêu cầu mới", onClick: () => setFormOpen(true) }
            }
          />
        ) : (
          <div className="grid gap-2">
            {list.map((item) => {
              const status = SUPPORT_STATUS[item.status] ?? {
                label: item.status,
                tone: "neutral" as const,
              };
              const open = item.status === "Open" || item.status === "Assigned";
              return (
                <article key={item.id} className="bg-surface p-5">
                  <div className="flex items-start justify-between gap-4 max-[760px]:flex-col">
                    <div className="min-w-0">
                      <span className="text-label text-muted">
                        #{item.id} ·{" "}
                        {SUPPORT_CATEGORY_LABEL[item.category as SupportCategory] ?? item.category}
                        {item.contractCode ? ` · ${item.contractCode}` : ""}
                        {item.unitCode ? ` · Khoang ${item.unitCode}` : ""}
                      </span>
                      <h2 className="mt-1 text-section text-ink">{item.title}</h2>
                      <p className="mt-0.5 text-label text-muted">
                        {item.facilityName ?? "—"} · Gửi lúc {formatDateTime(item.createdAt)}
                      </p>
                    </div>
                    <StatusTag tone={status.tone}>{status.label}</StatusTag>
                  </div>
                  <p className="mt-3 whitespace-pre-line text-small text-ink">{item.description}</p>
                  {item.photos && item.photos.length > 0 && (
                    <div className="mt-3 flex flex-wrap gap-2">
                      {item.photos.map((url) => (
                        <a key={url} href={url} target="_blank" rel="noreferrer">
                          <img src={url} alt="Ảnh đính kèm" className="h-16 w-16 object-cover" />
                        </a>
                      ))}
                    </div>
                  )}
                  {item.assignedStaffName && (
                    <p className="mt-3 text-label text-muted">
                      Nhân viên phụ trách: {item.assignedStaffName}
                      {item.assignedAt ? ` · từ ${formatDateTime(item.assignedAt)}` : ""}
                    </p>
                  )}
                  {item.resolution && (
                    <div className="mt-3 bg-surface-subtle p-3">
                      <span className="text-label text-muted">
                        Kết quả xử lý · {formatDateTime(item.resolvedAt)}
                      </span>
                      <p className="mt-1 whitespace-pre-line text-small text-ink">
                        {item.resolution}
                      </p>
                    </div>
                  )}
                  {item.feeInvoice && (
                    <div className="mt-3 flex flex-wrap items-center justify-between gap-3 bg-surface-subtle p-3">
                      <div>
                        <span className="text-label text-muted">
                          Phí dịch vụ · {item.feeInvoice.code}
                        </span>
                        <strong className="block text-data text-ink">
                          {formatMoney(item.feeInvoice.amount)}
                        </strong>
                      </div>
                      <div className="flex items-center gap-2">
                        <StatusTag tone={INVOICE_STATUS[item.feeInvoice.status]?.tone ?? "neutral"}>
                          {INVOICE_STATUS[item.feeInvoice.status]?.label ?? item.feeInvoice.status}
                        </StatusTag>
                        {item.feeInvoice.status === "Unpaid" && (
                          <Button
                            size="sm"
                            pending={payment.payingId === item.feeInvoice.invoiceId}
                            onClick={() =>
                              item.feeInvoice && payment.pay(item.feeInvoice.invoiceId)
                            }
                          >
                            Thanh toán
                          </Button>
                        )}
                      </div>
                    </div>
                  )}
                  {(open || item.contractId) && (
                    <div className="mt-4 flex flex-wrap gap-2">
                      {item.contractId && (
                        <Button
                          variant="secondary"
                          size="sm"
                          onClick={() => navigate(`/my-storage/${item.contractId}`)}
                        >
                          Xem hợp đồng
                        </Button>
                      )}
                      {open && (
                        <Button
                          variant="danger"
                          size="sm"
                          onClick={() => {
                            setNotice(null);
                            setCancelTarget(item);
                          }}
                        >
                          Huỷ yêu cầu
                        </Button>
                      )}
                    </div>
                  )}
                </article>
              );
            })}
          </div>
        )}
      </section>

      {cancelTarget && (
        <ConfirmDialog
          title="Huỷ yêu cầu hỗ trợ?"
          description={`Yêu cầu "${cancelTarget.title}" sẽ không được xử lý nữa.`}
          confirmLabel="Huỷ yêu cầu"
          cancelLabel="Giữ yêu cầu"
          isPending={canceling}
          onConfirm={confirmCancel}
          onCancel={() => setCancelTarget(null)}
        />
      )}
    </div>
  );
}

function CreateForm({
  onCancel,
  onCreated,
}: {
  onCancel: () => void;
  onCreated: (created: SupportView) => void;
}) {
  const rentals = useResource(fetchRentals);
  const facilities = useResource(fetchFacilities);
  const [category, setCategory] = useState<SupportCategory>("UNIT_ISSUE");
  const [contractId, setContractId] = useState("");
  const [facilityId, setFacilityId] = useState("");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [photos, setPhotos] = useState<string[]>([]);
  const [batchId, setBatchId] = useState<string | undefined>(undefined);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const contracts = rentals.data ?? [];
  const needsContract = category === "LOST_KEY";
  const missingTarget = needsContract ? !contractId : !contractId && !facilityId;

  async function addPhotos(files: FileList | null) {
    if (!files || files.length === 0) return;
    setUploading(true);
    setUploadError(null);
    let batch = batchId;
    const added: string[] = [];
    try {
      for (const file of Array.from(files).slice(0, MAX_PHOTOS - photos.length)) {
        const uploaded = await uploadMedia(file, batch);
        batch = uploaded.batchId;
        added.push(uploaded.url);
      }
    } catch (caught) {
      setUploadError(errorMessage(caught));
    } finally {
      setBatchId(batch);
      setPhotos((current) => [...current, ...added].slice(0, MAX_PHOTOS));
      setUploading(false);
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setSubmitError(null);
    try {
      const created = await createSupportRequest({
        contractId: contractId ? Number(contractId) : null,
        facilityId: !contractId && facilityId ? Number(facilityId) : null,
        category,
        title: title.trim(),
        description: description.trim(),
        photos,
      });
      if (batchId && photos.length > 0) {
        // Khoá batch ảnh đã gắn vào yêu cầu; lỗi ở bước này không ảnh hưởng yêu cầu đã tạo.
        await attachMediaBatch(batchId).catch(() => undefined);
      }
      onCreated(created);
    } catch (caught) {
      setSubmitError(errorMessage(caught));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="border border-[var(--container-border)] bg-surface p-6" onSubmit={submit}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-section text-ink">Yêu cầu hỗ trợ mới</h2>
          <p className="mt-1 text-small text-muted">Các trường có dấu * là bắt buộc.</p>
        </div>
        <button
          type="button"
          className="grid h-9 w-9 place-items-center border-0 bg-transparent text-muted hover:text-ink"
          onClick={onCancel}
          aria-label="Đóng"
        >
          <X size={19} />
        </button>
      </div>

      <div className="mt-5 grid grid-cols-2 gap-x-4 gap-y-4 max-[760px]:grid-cols-1">
        <label className="grid gap-1.5">
          <span className={FIELD_LABEL}>Loại yêu cầu *</span>
          <div className={INPUT_WRAP}>
            <select
              className={INPUT_ELEMENT}
              value={category}
              onChange={(event) => setCategory(event.target.value as SupportCategory)}
            >
              {CATEGORIES.map((value) => (
                <option key={value} value={value}>
                  {SUPPORT_CATEGORY_LABEL[value]}
                </option>
              ))}
            </select>
          </div>
        </label>

        <label className="grid gap-1.5">
          <span className={FIELD_LABEL}>Hợp đồng liên quan{needsContract ? " *" : ""}</span>
          <div className={INPUT_WRAP}>
            <select
              className={INPUT_ELEMENT}
              value={contractId}
              onChange={(event) => setContractId(event.target.value)}
              disabled={rentals.loading && !rentals.data}
            >
              <option value="">
                {rentals.loading && !rentals.data ? "Đang tải hợp đồng…" : "Không gắn hợp đồng"}
              </option>
              {contracts.map((rental) => (
                <option key={rental.contractId} value={String(rental.contractId)}>
                  {rental.contractCode} · Khoang {rental.unitCode}
                </option>
              ))}
            </select>
          </div>
          {rentals.error && <span className="text-label text-danger">{rentals.error}</span>}
          {!rentals.loading && rentals.data && contracts.length === 0 && (
            <span className="text-label text-muted">Bạn chưa có hợp đồng đang hoạt động.</span>
          )}
        </label>

        {!contractId && !needsContract && (
          <label className="grid gap-1.5">
            <span className={FIELD_LABEL}>Cơ sở *</span>
            <div className={INPUT_WRAP}>
              <select
                className={INPUT_ELEMENT}
                value={facilityId}
                onChange={(event) => setFacilityId(event.target.value)}
              >
                <option value="">
                  {facilities.loading && !facilities.data ? "Đang tải cơ sở…" : "Chọn cơ sở"}
                </option>
                {(facilities.data ?? []).map((facility) => (
                  <option key={facility.id} value={String(facility.id)}>
                    {facility.name}
                  </option>
                ))}
              </select>
            </div>
            {facilities.error && <span className="text-label text-danger">{facilities.error}</span>}
          </label>
        )}

        <label className="col-span-2 grid gap-1.5 max-[760px]:col-span-1">
          <span className={FIELD_LABEL}>Tiêu đề *</span>
          <div className={INPUT_WRAP}>
            <input
              className={INPUT_ELEMENT}
              required
              maxLength={255}
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="Ví dụ: Mất 1 chìa khoá khoang A-01"
            />
          </div>
        </label>

        <label className="col-span-2 grid gap-1.5 max-[760px]:col-span-1">
          <span className={FIELD_LABEL}>Mô tả *</span>
          <textarea
            required
            maxLength={4000}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            className="min-h-[96px] rounded-lg border border-rule-strong bg-surface p-2.5 text-small text-ink"
            placeholder="Mô tả vấn đề, thời điểm phát hiện và điều bạn cần cơ sở hỗ trợ."
          />
        </label>
      </div>

      <div className="mt-4">
        <span className={FIELD_LABEL}>
          Ảnh đính kèm ({photos.length}/{MAX_PHOTOS})
        </span>
        <div className="flex flex-wrap items-center gap-2">
          {photos.map((url) => (
            <div key={url} className="relative">
              <img src={url} alt="Ảnh đính kèm" className="h-16 w-16 object-cover" />
              <button
                type="button"
                className="absolute right-0 top-0 grid h-5 w-5 place-items-center border-0 bg-[var(--dark)] text-[var(--on-dark-ink)]"
                onClick={() => setPhotos((current) => current.filter((item) => item !== url))}
                aria-label="Bỏ ảnh"
              >
                <X size={12} />
              </button>
            </div>
          ))}
          {photos.length < MAX_PHOTOS && (
            <label
              className={`inline-flex min-h-[64px] cursor-pointer items-center gap-2 border-2 border-dashed border-rule-strong px-3 text-small text-muted hover:text-ink ${uploading ? "pointer-events-none opacity-60" : ""}`}
            >
              <ImagePlus size={16} /> {uploading ? "Đang tải ảnh…" : "Thêm ảnh"}
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                multiple
                className="sr-only"
                onChange={(event) => {
                  void addPhotos(event.target.files);
                  event.target.value = "";
                }}
              />
            </label>
          )}
        </div>
        {uploadError && <DemoNotice tone="error">{uploadError}</DemoNotice>}
      </div>

      {needsContract && !contractId && (
        <DemoNotice tone="info">Yêu cầu mất chìa khoá cần chọn hợp đồng liên quan.</DemoNotice>
      )}
      {submitError && <DemoNotice tone="error">{submitError}</DemoNotice>}

      <div className="mt-5 flex flex-wrap justify-end gap-2.5">
        <Button variant="secondary" onClick={onCancel} disabled={submitting}>
          Huỷ
        </Button>
        <Button
          type="submit"
          pending={submitting}
          disabled={uploading || missingTarget || !title.trim() || !description.trim()}
        >
          Gửi yêu cầu
        </Button>
      </div>
    </form>
  );
}
