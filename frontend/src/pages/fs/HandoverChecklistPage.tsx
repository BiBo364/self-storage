import { ArrowLeft, Check, Upload, X } from "lucide-react";
import { useEffect, useRef, useState, type ChangeEvent } from "react";
import { ApiError } from "../../app/api";
import { readFsUploadState, writeFsUploadState, type UploadedMedia } from "../../app/fsUploadStore";
import { attachMediaBatch, deleteMediaBatch, uploadMedia } from "../../app/media";
import type { Navigate } from "../../app/types";
import { Button, buttonClassName } from "../../components/ui/Button";
import { DemoNotice } from "../../components/ui/DemoNotice";
import { StatusTag } from "../../components/ui/StatusTag";
import { SurfaceState } from "../../components/ui/SurfaceState";
import { formatPrice } from "../../mocks/catalog";
import { findAppointment } from "../../mocks/fs";

function isAbortError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "name" in error &&
    (error as { name?: unknown }).name === "AbortError"
  );
}

function BackLink({ navigate }: { navigate: Navigate }) {
  return (
    <button
      className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-label font-semibold text-muted hover:text-ink"
      onClick={() => navigate("/staff/schedule")}
    >
      <ArrowLeft size={15} /> Về lịch làm việc
    </button>
  );
}

export function HandoverChecklistPage({ navigate }: { navigate: Navigate }) {
  const appointmentId = window.location.pathname.split("/")[3] ?? "";
  const appointment = findAppointment(appointmentId);

  const [identity, setIdentity] = useState<"Pending" | "Verified" | "Failed">("Pending");
  const [inspection, setInspection] = useState<"Pending" | "Agreed" | "Rejected">("Pending");
  const [contract, setContract] = useState<"Draft" | "Signed">("Draft");
  const [payment, setPayment] = useState<"Unpaid" | "Paid">("Unpaid");
  const [completed, setCompleted] = useState(false);
  const [inspectionNotes, setInspectionNotes] = useState("");
  const [rejectReason, setRejectReason] = useState("");
  const [rejectOpen, setRejectOpen] = useState(false);
  const [contractGenerated, setContractGenerated] = useState(false);
  const [accessType, setAccessType] = useState("PhysicalKey");
  const [quantity, setQuantity] = useState(1);
  const initialUploads = readFsUploadState(appointmentId);
  const [inspectionPhotos, setInspectionPhotos] = useState<UploadedMedia[]>(
    initialUploads.inspectionPhotos,
  );
  const [inspectionUploading, setInspectionUploading] = useState(false);
  const [inspectionUploadError, setInspectionUploadError] = useState<string | null>(null);
  const [inspectionAttaching, setInspectionAttaching] = useState(false);
  const [inspectionAttachError, setInspectionAttachError] = useState<string | null>(null);
  const [contractFile, setContractFile] = useState<UploadedMedia | null>(
    initialUploads.contractFile,
  );
  const [contractUploading, setContractUploading] = useState(false);
  const [contractUploadError, setContractUploadError] = useState<string | null>(null);
  const [contractAttaching, setContractAttaching] = useState(false);
  const [contractAttachError, setContractAttachError] = useState<string | null>(null);

  const inspectionUploadController = useRef<AbortController | null>(null);
  const contractUploadController = useRef<AbortController | null>(null);

  useEffect(() => {
    writeFsUploadState(appointmentId, { inspectionPhotos, contractFile });
  }, [appointmentId, inspectionPhotos, contractFile]);

  function cancelInspectionUpload() {
    inspectionUploadController.current?.abort();
  }

  function cancelContractUpload() {
    contractUploadController.current?.abort();
  }

  async function removeInspectionPhoto(photo: UploadedMedia) {
    try {
      const result = await deleteMediaBatch(photo.batchId);
      if (result.deleted === 0 && result.skipped > 0) {
        setInspectionUploadError("File đã được gắn với bước trước, không xoá được.");
        return;
      }
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 404)) {
        setInspectionUploadError(error instanceof Error ? error.message : "Không xoá được ảnh.");
        return;
      }
    }
    setInspectionPhotos((current) => current.filter((item) => item.url !== photo.url));
    setInspectionUploadError(null);
  }

  async function removeContractFile() {
    if (!contractFile) return;
    try {
      const result = await deleteMediaBatch(contractFile.batchId);
      if (result.deleted === 0 && result.skipped > 0) {
        setContractUploadError("File đã được gắn với bước trước, không xoá được.");
        return;
      }
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 404)) {
        setContractUploadError(
          error instanceof Error ? error.message : "Không xoá được file hợp đồng.",
        );
        return;
      }
    }
    setContractFile(null);
    setContractUploadError(null);
  }

  async function attachInspectionBatches() {
    const batchIds = [...new Set(inspectionPhotos.map((photo) => photo.batchId))];
    if (batchIds.length === 0) return true;
    setInspectionAttaching(true);
    setInspectionAttachError(null);
    try {
      await Promise.all(batchIds.map((batchId) => attachMediaBatch(batchId)));
      return true;
    } catch (error) {
      setInspectionAttachError(
        error instanceof Error ? error.message : "Không gắn được batch ảnh.",
      );
      return false;
    } finally {
      setInspectionAttaching(false);
    }
  }

  async function acceptInspection() {
    if (await attachInspectionBatches()) {
      setInspection("Agreed");
    }
  }

  async function rejectInspection() {
    const batchIds = [...new Set(inspectionPhotos.map((photo) => photo.batchId))];
    await Promise.allSettled(batchIds.map((batchId) => attachMediaBatch(batchId)));
    setInspection("Rejected");
  }

  async function signContract() {
    if (contractFile) {
      setContractAttaching(true);
      setContractAttachError(null);
      try {
        await attachMediaBatch(contractFile.batchId);
      } catch (error) {
        setContractAttachError(
          error instanceof Error ? error.message : "Không gắn được batch hợp đồng.",
        );
        return;
      } finally {
        setContractAttaching(false);
      }
    }
    setContract("Signed");
  }

  async function uploadInspectionPhotos(event: ChangeEvent<HTMLInputElement>) {
    const input = event.currentTarget;
    const files = Array.from(input.files ?? []);
    if (files.length === 0) return;
    inspectionUploadController.current?.abort();
    const controller = new AbortController();
    inspectionUploadController.current = controller;
    setInspectionUploading(true);
    setInspectionUploadError(null);
    try {
      const results = await Promise.allSettled(
        files.map(async (file) => {
          const result = await uploadMedia(file, undefined, controller.signal);
          return {
            url: result.url,
            publicId: result.publicId,
            name: file.name,
            format: result.format,
            batchId: result.batchId,
          };
        }),
      );
      const uploaded = results
        .filter(
          (result): result is PromiseFulfilledResult<UploadedMedia> =>
            result.status === "fulfilled",
        )
        .map((result) => result.value);
      const failed = results.filter(
        (result): result is PromiseRejectedResult =>
          result.status === "rejected" && !isAbortError(result.reason),
      );

      if (uploaded.length > 0) {
        setInspectionPhotos((current) => [...current, ...uploaded]);
        const stored = readFsUploadState(appointmentId);
        writeFsUploadState(appointmentId, {
          inspectionPhotos: [...stored.inspectionPhotos, ...uploaded],
          contractFile: stored.contractFile,
        });
      }
      if (failed.length > 0) {
        const firstMessage =
          failed[0].reason instanceof Error ? failed[0].reason.message : "Không tải được ảnh.";
        setInspectionUploadError(
          failed.length === 1
            ? `1 ảnh không tải được: ${firstMessage}`
            : `${failed.length} ảnh không tải được. Lỗi đầu tiên: ${firstMessage}`,
        );
      }
    } finally {
      if (inspectionUploadController.current === controller) {
        inspectionUploadController.current = null;
        setInspectionUploading(false);
      }
      input.value = "";
    }
  }

  async function uploadContractFile(event: ChangeEvent<HTMLInputElement>) {
    const input = event.currentTarget;
    const file = input.files?.[0];
    if (!file) return;
    contractUploadController.current?.abort();
    const controller = new AbortController();
    contractUploadController.current = controller;
    setContractUploading(true);
    setContractUploadError(null);
    try {
      const result = await uploadMedia(file, undefined, controller.signal);
      const uploaded = {
        url: result.url,
        publicId: result.publicId,
        name: file.name,
        format: result.format,
        batchId: result.batchId,
      };
      setContractFile(uploaded);
      const stored = readFsUploadState(appointmentId);
      writeFsUploadState(appointmentId, {
        inspectionPhotos: stored.inspectionPhotos,
        contractFile: uploaded,
      });
    } catch (error) {
      if (!isAbortError(error)) {
        setContractUploadError(error instanceof Error ? error.message : "Không tải được file.");
      }
    } finally {
      if (contractUploadController.current === controller) {
        contractUploadController.current = null;
        setContractUploading(false);
      }
      input.value = "";
    }
  }

  if (!appointment) {
    return (
      <div className="grid gap-6">
        <BackLink navigate={navigate} />
        <SurfaceState
          variant="not-found"
          title="Không tìm thấy lịch hẹn"
          description="Lịch hẹn có thể đã bị huỷ hoặc không thuộc cơ sở của bạn."
          action={{ label: "Về lịch làm việc", onClick: () => navigate("/staff/schedule") }}
        />
      </div>
    );
  }

  if (appointment.type !== "CHECKIN") {
    return (
      <div className="grid gap-6">
        <BackLink navigate={navigate} />
        <SurfaceState
          variant="empty"
          title="Checklist trả kho chưa có"
          description="Prototype mới làm checklist bàn giao. Checklist trả kho thuộc Flow 2.a, đang chờ chốt spec."
          action={{ label: "Về lịch làm việc", onClick: () => navigate("/staff/schedule") }}
        />
      </div>
    );
  }

  if (appointment.status === "Done") {
    return (
      <div className="grid gap-6">
        <BackLink navigate={navigate} />
        <SurfaceState
          variant="empty"
          title="Bàn giao đã hoàn tất"
          description={`${appointment.unitCode} đã bàn giao cho ${appointment.customerName}.`}
          action={{ label: "Về lịch làm việc", onClick: () => navigate("/staff/schedule") }}
        />
      </div>
    );
  }

  if (identity === "Failed") {
    return (
      <div className="grid gap-6">
        <BackLink navigate={navigate} />
        <SurfaceState
          variant="error"
          title="Xác minh danh tính không đạt"
          description="Đã ghi nhận. Lịch hẹn không bị huỷ; khách cần liên hệ FM để xử lý."
          action={{ label: "Về lịch làm việc", onClick: () => navigate("/staff/schedule") }}
        />
      </div>
    );
  }

  if (inspection === "Rejected") {
    return (
      <div className="grid gap-6">
        <BackLink navigate={navigate} />
        <SurfaceState
          variant="error"
          title="Khách từ chối khoang"
          description="Đã ghi nhận từ chối. FM sẽ đề xuất khoang khác; checklist dừng tại đây."
          action={{ label: "Về lịch làm việc", onClick: () => navigate("/staff/schedule") }}
        />
      </div>
    );
  }

  if (completed) {
    return (
      <div className="grid gap-6">
        <div>
          <BackLink navigate={navigate} />
          <h1 className="mb-0 mt-3 text-title text-ink">Bàn giao hoàn tất</h1>
          <p className="mt-1.5 text-small text-muted">
            {appointment.customerName} · {appointment.unitCode}
          </p>
        </div>
        <div className="border border-success-line bg-success-soft p-6 text-success">
          <Check size={28} />
          <h2 className="mb-0 mt-3 text-section">Đã bàn giao khoang {appointment.unitCode}</h2>
          <p className="mt-1 text-small">
            Biên bản đã chốt. Khoang chuyển sang trạng thái Đang thuê.
          </p>
        </div>
        <Button className="w-fit" onClick={() => navigate("/staff/schedule")}>
          Về lịch làm việc
        </Button>
      </div>
    );
  }

  const steps = [
    { title: "Xác minh danh tính", done: identity === "Verified" },
    { title: "Kiểm tra khoang", done: inspection === "Agreed" },
    { title: "Ký hợp đồng", done: contract === "Signed" },
    { title: "Thanh toán tháng đầu", done: payment === "Paid" },
    { title: "Bàn giao khoá", done: completed },
  ];
  const currentStep =
    identity !== "Verified"
      ? 0
      : inspection !== "Agreed"
        ? 1
        : contract !== "Signed"
          ? 2
          : payment !== "Paid"
            ? 3
            : 4;

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <BackLink navigate={navigate} />
          <h1 className="mb-0 mt-3 text-title text-ink">Checklist bàn giao</h1>
          <p className="mt-1.5 text-small text-muted">
            {appointment.customerName} · {appointment.unitCode} · {appointment.timeSlot}
          </p>
        </div>
        <StatusTag tone="warning">Đang bàn giao</StatusTag>
      </div>

      <DemoNotice tone="info">
        Prototype — dữ liệu minh hoạ; API handover chưa có. Upload ảnh/hợp đồng đã nối API thật.
      </DemoNotice>

      <div className="grid grid-cols-[320px_minmax(0,1fr)] items-start gap-6 max-[980px]:grid-cols-1">
        <aside className="grid gap-4">
          <div className="border border-border bg-surface p-5">
            <span className="label text-muted">Thông tin đơn</span>
            <h2 className="mb-0 mt-1 text-section text-ink">{appointment.customerName}</h2>
            <dl className="mt-3 grid gap-2 text-small">
              <div className="flex items-center justify-between gap-3">
                <dt className="text-muted">Khoang</dt>
                <dd className="m-0 font-semibold text-ink">{appointment.unitCode}</dd>
              </div>
              <div className="flex items-center justify-between gap-3">
                <dt className="text-muted">Loại khoang</dt>
                <dd className="m-0 text-ink">{appointment.unitType}</dd>
              </div>
              <div className="flex items-center justify-between gap-3">
                <dt className="text-muted">Giờ hẹn</dt>
                <dd className="m-0 text-ink">{appointment.timeSlot}</dd>
              </div>
              <div className="flex items-center justify-between gap-3">
                <dt className="text-muted">Mã đơn</dt>
                <dd className="m-0 font-mono text-ink">{appointment.orderCode}</dd>
              </div>
              <div className="flex items-start justify-between gap-3">
                <dt className="text-muted">Cơ sở</dt>
                <dd className="m-0 text-right text-ink">{appointment.facility}</dd>
              </div>
            </dl>
            {appointment.note && (
              <p className="mb-0 mt-3 border-t border-border pt-3 text-label text-muted">
                {appointment.note}
              </p>
            )}
          </div>

          <ol className="grid gap-3 border border-border bg-surface p-5">
            {steps.map((step, index) => {
              const isDone = step.done;
              const isCurrent = index === currentStep;
              return (
                <li key={step.title} className="flex items-start gap-3">
                  <span
                    className={`grid h-7 w-7 shrink-0 place-items-center text-label font-bold ${
                      isDone
                        ? "bg-brand text-signal-ink"
                        : isCurrent
                          ? "border border-brand text-brand"
                          : "border border-border text-muted"
                    }`}
                  >
                    {isDone ? <Check size={14} /> : index + 1}
                  </span>
                  <div>
                    <strong
                      className={`block text-small ${isDone || isCurrent ? "text-ink" : "text-muted"}`}
                    >
                      {step.title}
                    </strong>
                    <span className="mt-0.5 block text-label text-muted">
                      {isDone ? "Hoàn tất" : isCurrent ? "Đang thực hiện" : "Chưa làm"}
                    </span>
                  </div>
                </li>
              );
            })}
          </ol>
        </aside>

        <section className="border border-border bg-surface p-5">
          {currentStep === 0 && (
            <div>
              <span className="label text-muted">Bước 01</span>
              <h2 className="mb-0 mt-1 text-section text-ink">Xác minh danh tính</h2>
              <p className="mt-2 text-small leading-relaxed text-muted">
                Đối chiếu CCCD/Passport bản gốc với thông tin khách trên đơn.
              </p>
              <div className="mt-4 border border-border bg-surface-subtle p-4 text-small">
                <strong className="block text-ink">{appointment.customerName}</strong>
                <span className="mt-1 block text-muted">
                  {appointment.customerPhone} · {appointment.customerEmail}
                </span>
              </div>
              <div className="mt-5 flex flex-wrap gap-2">
                <Button onClick={() => setIdentity("Verified")}>
                  <Check size={16} /> Xác minh đạt
                </Button>
                <Button variant="secondary" onClick={() => setIdentity("Failed")}>
                  Không đạt
                </Button>
              </div>
            </div>
          )}

          {currentStep === 1 && (
            <div>
              <span className="label text-muted">Bước 02</span>
              <h2 className="mb-0 mt-1 text-section text-ink">Kiểm tra khoang</h2>
              <p className="mt-2 text-small leading-relaxed text-muted">
                Ghi chú hiện trạng và ảnh chụp trước khi khách xác nhận.
              </p>
              <label className="mt-4 grid gap-1.5 text-label text-muted">
                Ghi chú hiện trạng
                <textarea
                  className="min-h-[90px] border border-border bg-surface p-3 text-small text-ink"
                  value={inspectionNotes}
                  onChange={(event) => setInspectionNotes(event.target.value)}
                  placeholder="Ví dụ: khoang sạch, cửa khoá hoạt động, có vết xước nhỏ ở tường trái."
                />
              </label>
              <div className="mt-3 border border-dashed border-border bg-surface-subtle p-4">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <span className="text-label text-muted">
                    Ảnh hiện trạng (JPEG/PNG/WebP/PDF, ≤ 10MB)
                  </span>
                  <label className={`${buttonClassName("secondary", "sm")} cursor-pointer`}>
                    <Upload size={16} /> {inspectionUploading ? "Đang tải..." : "Tải ảnh"}
                    <input
                      type="file"
                      accept="image/jpeg,image/png,image/webp,application/pdf"
                      multiple
                      className="hidden"
                      disabled={inspectionUploading}
                      onChange={uploadInspectionPhotos}
                    />
                  </label>
                  {inspectionUploading && (
                    <button
                      type="button"
                      className="border-0 bg-transparent p-0 text-label font-semibold text-danger hover:underline"
                      onClick={cancelInspectionUpload}
                    >
                      Hủy
                    </button>
                  )}
                </div>
                {inspectionUploadError && (
                  <p className="mt-2 text-label text-danger" role="alert">
                    {inspectionUploadError}
                  </p>
                )}
                {inspectionPhotos.length > 0 && (
                  <div className="mt-3 flex flex-wrap gap-2">
                    {inspectionPhotos.map((photo) => (
                      <div key={photo.url} className="relative h-20 w-20">
                        <a
                          href={photo.url}
                          target="_blank"
                          rel="noreferrer"
                          className="block h-full w-full border border-border"
                        >
                          {photo.format === "pdf" ? (
                            <span className="grid h-full w-full place-items-center bg-surface text-label text-muted">
                              PDF
                            </span>
                          ) : (
                            <img
                              src={photo.url}
                              alt={photo.name}
                              className="h-full w-full object-cover"
                            />
                          )}
                        </a>
                        <button
                          type="button"
                          className="absolute -right-2 -top-2 grid h-6 w-6 place-items-center border border-border bg-surface text-danger hover:border-danger"
                          onClick={() => removeInspectionPhoto(photo)}
                          aria-label={`Xoá ảnh ${photo.name}`}
                        >
                          <X size={12} />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
              {inspectionAttachError && (
                <DemoNotice tone="error">{inspectionAttachError}</DemoNotice>
              )}
              {rejectOpen ? (
                <div className="mt-4 border border-border bg-surface-subtle p-4">
                  <label className="grid gap-1.5 text-label text-muted">
                    Lý do từ chối
                    <textarea
                      className="min-h-[70px] border border-border bg-surface p-3 text-small text-ink"
                      value={rejectReason}
                      onChange={(event) => setRejectReason(event.target.value)}
                      placeholder="Khách từ chối vì..."
                    />
                  </label>
                  <div className="mt-3 flex flex-wrap gap-2">
                    <Button
                      variant="danger"
                      disabled={!rejectReason.trim()}
                      onClick={rejectInspection}
                    >
                      Xác nhận từ chối
                    </Button>
                    <Button variant="secondary" onClick={() => setRejectOpen(false)}>
                      Huỷ
                    </Button>
                  </div>
                </div>
              ) : (
                <div className="mt-5 flex flex-wrap gap-2">
                  <Button pending={inspectionAttaching} onClick={acceptInspection}>
                    <Check size={16} /> Khách đồng ý
                  </Button>
                  <Button variant="secondary" onClick={() => setRejectOpen(true)}>
                    Khách từ chối
                  </Button>
                </div>
              )}
            </div>
          )}

          {currentStep === 2 && (
            <div>
              <span className="label text-muted">Bước 03</span>
              <h2 className="mb-0 mt-1 text-section text-ink">Ký hợp đồng</h2>
              <p className="mt-2 text-small leading-relaxed text-muted">
                Sinh hợp đồng từ mẫu đang hiệu lực, in cho khách ký và tải bản scan lên.
              </p>
              {!contractGenerated ? (
                <Button className="mt-4" onClick={() => setContractGenerated(true)}>
                  Sinh hợp đồng
                </Button>
              ) : (
                <>
                  <div className="mt-4 grid gap-2 border border-border bg-surface-subtle p-4 text-small">
                    <div className="flex justify-between gap-3">
                      <span className="text-muted">Mã hợp đồng</span>
                      <strong className="text-ink">HD-{appointment.orderCode}</strong>
                    </div>
                    <div className="flex justify-between gap-3">
                      <span className="text-muted">Khách hàng</span>
                      <span className="text-ink">{appointment.customerName}</span>
                    </div>
                    <div className="flex justify-between gap-3">
                      <span className="text-muted">Khoang</span>
                      <span className="text-ink">{appointment.unitCode}</span>
                    </div>
                    <div className="flex justify-between gap-3">
                      <span className="text-muted">Giá thuê</span>
                      <strong className="text-ink">
                        {formatPrice(appointment.monthlyPrice)} đ / tháng
                      </strong>
                    </div>
                    <div className="flex justify-between gap-3">
                      <span className="text-muted">Tiền cọc đã đóng</span>
                      <span className="text-ink">{formatPrice(appointment.depositAmount)} đ</span>
                    </div>
                  </div>
                  <div className="mt-3 border border-dashed border-border bg-surface-subtle p-4">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <span className="text-label text-muted">
                        Bản scan hợp đồng đã ký (PDF/ảnh, ≤ 10MB)
                      </span>
                      <label className={`${buttonClassName("secondary", "sm")} cursor-pointer`}>
                        <Upload size={16} /> {contractUploading ? "Đang tải..." : "Tải bản scan"}
                        <input
                          type="file"
                          accept="image/jpeg,image/png,image/webp,application/pdf"
                          className="hidden"
                          disabled={contractUploading}
                          onChange={uploadContractFile}
                        />
                      </label>
                      {contractUploading && (
                        <button
                          type="button"
                          className="border-0 bg-transparent p-0 text-label font-semibold text-danger hover:underline"
                          onClick={cancelContractUpload}
                        >
                          Hủy
                        </button>
                      )}
                    </div>
                    {contractUploadError && (
                      <p className="mt-2 text-label text-danger" role="alert">
                        {contractUploadError}
                      </p>
                    )}
                    {contractFile && (
                      <div className="mt-3 flex items-center gap-2">
                        <a
                          href={contractFile.url}
                          target="_blank"
                          rel="noreferrer"
                          className="flex items-center gap-2 text-label font-semibold text-accent hover:underline"
                        >
                          <Upload size={14} /> {contractFile.name}
                        </a>
                        <button
                          type="button"
                          className="grid h-7 w-7 place-items-center border border-border bg-surface text-danger hover:border-danger"
                          onClick={removeContractFile}
                          aria-label="Xoá file hợp đồng"
                        >
                          <X size={13} />
                        </button>
                      </div>
                    )}
                  </div>
                  {contractAttachError && (
                    <DemoNotice tone="error">{contractAttachError}</DemoNotice>
                  )}
                  <Button
                    className="mt-4"
                    disabled={!contractFile || contractAttaching}
                    pending={contractAttaching}
                    onClick={signContract}
                  >
                    <Check size={16} /> Xác nhận đã ký
                  </Button>
                </>
              )}
            </div>
          )}

          {currentStep === 3 && (
            <div>
              <span className="label text-muted">Bước 04</span>
              <h2 className="mb-0 mt-1 text-section text-ink">Thanh toán tháng đầu</h2>
              <p className="mt-2 text-small leading-relaxed text-muted">
                Trạng thái thật lấy từ IPN; ở prototype, bấm xác nhận để mô phỏng.
              </p>
              <div className="mt-4 border border-border bg-surface-subtle p-4">
                <div className="flex items-center justify-between gap-3 text-small">
                  <span className="text-muted">Tiền thuê tháng đầu</span>
                  <strong className="text-ink">{formatPrice(appointment.monthlyPrice)} đ</strong>
                </div>
                <div className="mt-2 flex items-center justify-between gap-3 text-small">
                  <span className="text-muted">Trạng thái</span>
                  <StatusTag tone="warning">Chờ thanh toán</StatusTag>
                </div>
              </div>
              <p className="mt-3 text-label text-muted">
                Prototype: không nhập tay trạng thái thanh toán.
              </p>
              <Button className="mt-4" onClick={() => setPayment("Paid")}>
                <Check size={16} /> Xác nhận đã thanh toán
              </Button>
            </div>
          )}

          {currentStep === 4 && (
            <div>
              <span className="label text-muted">Bước 05</span>
              <h2 className="mb-0 mt-1 text-section text-ink">Bàn giao khoá</h2>
              <p className="mt-2 text-small leading-relaxed text-muted">
                MVP chỉ hỗ trợ chìa khoá vật lý. Ghi số lượng chìa giao cho khách.
              </p>
              <div className="mt-4 grid gap-4">
                <label className="grid gap-1.5 text-label text-muted">
                  Hình thức bàn giao
                  <select
                    className="min-h-[42px] border border-border bg-surface px-3 text-small text-ink"
                    value={accessType}
                    onChange={(event) => setAccessType(event.target.value)}
                  >
                    <option value="PhysicalKey">Chìa khoá vật lý</option>
                  </select>
                </label>
                <label className="grid gap-1.5 text-label text-muted">
                  Số lượng chìa
                  <input
                    type="number"
                    min={1}
                    value={quantity}
                    onChange={(event) => setQuantity(Number(event.target.value))}
                    className="min-h-[42px] w-[120px] border border-border bg-surface px-3 text-small text-ink"
                  />
                </label>
              </div>
              <Button className="mt-5" onClick={() => setCompleted(true)}>
                <Check size={16} /> Hoàn tất bàn giao
              </Button>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
