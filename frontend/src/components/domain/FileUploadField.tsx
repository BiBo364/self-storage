import { Upload, X } from "lucide-react";
import { useState, type ChangeEvent, type Dispatch, type SetStateAction } from "react";
import { errorMessage } from "../../app/http";
import { attachMediaBatch, deleteMediaBatch, uploadMedia } from "../../app/media";
import { buttonClassName } from "../ui/Button";
import { ImageLightbox } from "../ui/ImageLightbox";
// ---------------------------------------------------------------- upload ảnh / file dùng chung

/** Một file đã upload qua /api/media. `batchId` null = file đã có sẵn trên server (đã attach). */
export type PickedFile = { url: string; name: string; isPdf: boolean; batchId: string | null };

export const TEXTAREA_CLASS =
  "min-h-[96px] w-full rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-body text-ink outline-0 transition-colors hover:border-ink focus:border-brand";
export const INPUT_CLASS =
  "min-h-[44px] w-full rounded-[2px] border border-rule-strong bg-surface px-3 text-body text-ink outline-0 transition-colors hover:border-ink focus:border-brand";

export function filesFromUrls(urls: string[] | null | undefined): PickedFile[] {
  return (urls ?? []).map((url) => ({
    url,
    name: url.split("/").pop() ?? url,
    isPdf: url.toLowerCase().endsWith(".pdf"),
    batchId: null,
  }));
}

/** Khoá các batch vừa upload sau khi flow đã nhận URL (batch bỏ ngang sẽ bị job dọn). */
export async function attachUploaded(files: PickedFile[]): Promise<void> {
  const batchIds = [
    ...new Set(files.map((file) => file.batchId).filter((id): id is string => !!id)),
  ];
  await Promise.allSettled(batchIds.map((batchId) => attachMediaBatch(batchId)));
}

const DEFAULT_ACCEPT = "image/jpeg,image/png,image/webp,application/pdf";

/**
 * Ô tải ảnh/PDF lên /api/media; JPEG/PNG/WebP/PDF ≤ 10MB do backend kiểm. Bấm ảnh để xem cỡ lớn.
 * `thumbSize="lg"` cho ảnh cần đọc chữ (giấy tờ tuỳ thân); `maxFiles` chặn chọn quá số file.
 */
export function FileUploadField({
  label,
  files,
  onChange,
  multiple = true,
  disabled = false,
  accept = DEFAULT_ACCEPT,
  thumbSize = "md",
  maxFiles,
  hint,
}: {
  label: string;
  files: PickedFile[];
  onChange: Dispatch<SetStateAction<PickedFile[]>>;
  multiple?: boolean;
  disabled?: boolean;
  accept?: string;
  thumbSize?: "md" | "lg";
  maxFiles?: number;
  hint?: string;
}) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<number | null>(null);
  const images = files.filter((file) => !file.isPdf);
  const isFull = maxFiles !== undefined && files.length >= maxFiles;

  async function pick(event: ChangeEvent<HTMLInputElement>) {
    const input = event.currentTarget;
    let chosen = Array.from(input.files ?? []);
    input.value = "";
    if (chosen.length === 0) return;
    setError(null);
    if (maxFiles !== undefined && files.length + chosen.length > maxFiles) {
      const room = Math.max(0, maxFiles - files.length);
      setError(`Tối đa ${maxFiles} file — chỉ tải thêm ${room} file đầu.`);
      chosen = chosen.slice(0, room);
      if (chosen.length === 0) return;
    }
    setUploading(true);
    const results = await Promise.allSettled(
      chosen.map(async (file): Promise<PickedFile> => {
        const result = await uploadMedia(file);
        return {
          url: result.url,
          name: file.name,
          isPdf: result.format === "pdf",
          batchId: result.batchId,
        };
      }),
    );
    const uploaded = results
      .filter((r): r is PromiseFulfilledResult<PickedFile> => r.status === "fulfilled")
      .map((r) => r.value);
    const failed = results.filter((r): r is PromiseRejectedResult => r.status === "rejected");
    if (uploaded.length > 0) {
      onChange((current) => (multiple ? [...current, ...uploaded] : uploaded.slice(-1)));
    }
    if (failed.length > 0) {
      setError(`${failed.length} file không tải được: ${errorMessage(failed[0].reason)}`);
    }
    setUploading(false);
  }

  async function remove(file: PickedFile) {
    if (file.batchId) {
      try {
        await deleteMediaBatch(file.batchId);
      } catch {
        // batch đã bị dọn hoặc đã attach — chỉ bỏ khỏi danh sách gửi đi
      }
    }
    onChange((current) => current.filter((item) => item.url !== file.url));
  }

  return (
    <div className="border border-dashed border-rule-strong bg-surface-subtle p-4 transition-colors hover:border-ink">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="grid gap-0.5">
          <span className="text-small font-semibold text-ink">{label}</span>
          {hint && <span className="text-label font-normal text-muted">{hint}</span>}
        </div>
        <label
          className={`${buttonClassName("secondary", "sm")} ${disabled || uploading || isFull ? "pointer-events-none opacity-60" : "cursor-pointer"}`}
        >
          <Upload size={15} className={uploading ? "animate-bounce" : undefined} />{" "}
          {uploading ? "Đang tải…" : multiple ? "Tải ảnh" : "Tải file"}
          <input
            type="file"
            accept={accept}
            multiple={multiple}
            className="hidden"
            disabled={disabled || uploading || isFull}
            onChange={pick}
          />
        </label>
      </div>
      {error && (
        <p className="mb-0 mt-2 text-label text-danger" role="alert">
          {error}
        </p>
      )}
      {files.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-3">
          {files.map((file) => (
            <div
              key={file.url}
              className={`pop-enter relative ${thumbSize === "lg" ? "h-40 w-60 max-[520px]:h-36 max-[520px]:w-full" : "h-24 w-24"}`}
            >
              {file.isPdf ? (
                <a
                  href={file.url}
                  target="_blank"
                  rel="noreferrer"
                  className="grid h-full w-full place-items-center border border-border bg-surface text-label text-muted hover:border-ink"
                  title={file.name}
                >
                  PDF
                </a>
              ) : (
                <button
                  type="button"
                  className="group block h-full w-full overflow-hidden border border-border bg-surface p-0 hover:border-ink"
                  title={`Xem lớn: ${file.name}`}
                  onClick={() => setPreview(images.findIndex((image) => image.url === file.url))}
                >
                  <img
                    src={file.url}
                    alt={file.name}
                    className={`h-full w-full ${thumbSize === "lg" ? "object-contain" : "object-cover"} transition-transform duration-300 ease-out group-hover:scale-105`}
                  />
                </button>
              )}
              {!disabled && (
                <button
                  type="button"
                  className="absolute -right-2 -top-2 grid h-7 w-7 place-items-center border border-border bg-surface text-danger hover:border-danger hover:bg-danger-soft"
                  onClick={() => remove(file)}
                  aria-label={`Bỏ ${file.name}`}
                >
                  <X size={14} />
                </button>
              )}
            </div>
          ))}
        </div>
      )}
      {preview !== null && images.length > 0 && (
        <ImageLightbox
          images={images}
          startIndex={Math.max(0, preview)}
          onClose={() => setPreview(null)}
        />
      )}
    </div>
  );
}
