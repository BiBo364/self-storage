import { Upload, X } from "lucide-react";
import { useState, type ChangeEvent, type Dispatch, type SetStateAction } from "react";
import { errorMessage } from "../../app/http";
import { attachMediaBatch, deleteMediaBatch, uploadMedia } from "../../app/media";
import { buttonClassName } from "../ui/Button";
// ---------------------------------------------------------------- upload ảnh / file dùng chung

/** Một file đã upload qua /api/media. `batchId` null = file đã có sẵn trên server (đã attach). */
export type PickedFile = { url: string; name: string; isPdf: boolean; batchId: string | null };

export const TEXTAREA_CLASS =
  "min-h-[90px] w-full rounded-[2px] border border-rule-strong bg-surface px-3 py-2.5 text-small text-ink outline-0 transition-colors focus:border-brand";
export const INPUT_CLASS =
  "min-h-[42px] w-full rounded-[2px] border border-rule-strong bg-surface px-3 text-small text-ink outline-0 focus:border-brand";

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

/** Ô tải ảnh/PDF lên /api/media; JPEG/PNG/WebP/PDF ≤ 10MB do backend kiểm. */
export function FileUploadField({
  label,
  files,
  onChange,
  multiple = true,
  disabled = false,
}: {
  label: string;
  files: PickedFile[];
  onChange: Dispatch<SetStateAction<PickedFile[]>>;
  multiple?: boolean;
  disabled?: boolean;
}) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function pick(event: ChangeEvent<HTMLInputElement>) {
    const input = event.currentTarget;
    const chosen = Array.from(input.files ?? []);
    input.value = "";
    if (chosen.length === 0) return;
    setUploading(true);
    setError(null);
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
    <div className="border border-dashed border-border bg-surface-subtle p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <span className="text-label text-muted">{label}</span>
        <label
          className={`${buttonClassName("secondary", "sm")} ${disabled || uploading ? "pointer-events-none opacity-60" : "cursor-pointer"}`}
        >
          <Upload size={15} /> {uploading ? "Đang tải…" : multiple ? "Tải ảnh" : "Tải file"}
          <input
            type="file"
            accept="image/jpeg,image/png,image/webp,application/pdf"
            multiple={multiple}
            className="hidden"
            disabled={disabled || uploading}
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
        <div className="mt-3 flex flex-wrap gap-2">
          {files.map((file) => (
            <div key={file.url} className="relative h-20 w-20">
              <a
                href={file.url}
                target="_blank"
                rel="noreferrer"
                className="block h-full w-full border border-border"
                title={file.name}
              >
                {file.isPdf ? (
                  <span className="grid h-full w-full place-items-center bg-surface text-label text-muted">
                    PDF
                  </span>
                ) : (
                  <img src={file.url} alt={file.name} className="h-full w-full object-cover" />
                )}
              </a>
              {!disabled && (
                <button
                  type="button"
                  className="absolute -right-2 -top-2 grid h-6 w-6 place-items-center border border-border bg-surface text-danger hover:border-danger"
                  onClick={() => remove(file)}
                  aria-label={`Bỏ ${file.name}`}
                >
                  <X size={12} />
                </button>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
