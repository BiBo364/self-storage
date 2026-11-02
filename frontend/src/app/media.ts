import { apiRequest, apiUpload } from "./api";
import { authGateway } from "./auth";

export type MediaUpload = {
  url: string;
  publicId: string;
  bytes: number;
  format: string;
  batchId: string;
};

export type MediaBatchDeleteResult = {
  deleted: number;
  skipped: number;
};

export type MediaBatchAttachResult = {
  attached: number;
};

/**
 * POST /api/media/uploads — trả URL Cloudinary để gắn vào flow.
 * File ≤ 10MB, JPEG/PNG/WebP/PDF. `batchId` gom các file cùng đợt để Hủy/attach.
 */
export function uploadMedia(
  file: File,
  batchId?: string,
  signal?: AbortSignal,
): Promise<MediaUpload> {
  return apiUpload<MediaUpload>(
    "/api/media/uploads",
    file,
    authGateway.getSession()?.token,
    signal,
    batchId ? { batchId } : undefined,
  );
}

/** DELETE /api/media/uploads/batch/{batchId} — dọn asset chưa attach. */
export function deleteMediaBatch(batchId: string): Promise<MediaBatchDeleteResult> {
  return apiRequest<MediaBatchDeleteResult>(`/api/media/batch/${encodeURIComponent(batchId)}`, {
    method: "DELETE",
    accessToken: authGateway.getSession()?.token,
  });
}

/** POST /api/media/uploads/batch/{batchId}/attach — khoá batch sau khi flow submit URL. */
export function attachMediaBatch(batchId: string): Promise<MediaBatchAttachResult> {
  return apiRequest<MediaBatchAttachResult>(
    `/api/media/batch/${encodeURIComponent(batchId)}/attach`,
    {
      method: "POST",
      accessToken: authGateway.getSession()?.token,
    },
  );
}
