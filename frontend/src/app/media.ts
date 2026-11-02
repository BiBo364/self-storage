import { apiUpload } from "./api";
import { authGateway } from "./auth";

export type MediaUpload = {
  url: string;
  publicId: string;
  bytes: number;
  format: string;
};

/**
 * POST /api/media/uploads — trả URL Cloudinary để gắn vào flow.
 * File ≤ 10MB, JPEG/PNG/WebP/PDF. Không ghi DB; flow tự lưu URL.
 */
export function uploadMedia(file: File, signal?: AbortSignal): Promise<MediaUpload> {
  return apiUpload<MediaUpload>(
    "/api/media/uploads",
    file,
    authGateway.getSession()?.token,
    signal,
  );
}
