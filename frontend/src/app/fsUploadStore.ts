export type UploadedMedia = {
  url: string;
  publicId: string;
  name: string;
  format: string;
  batchId: string;
};

export type FsUploadState = {
  inspectionPhotos: UploadedMedia[];
  contractFile: UploadedMedia | null;
};

const PREFIX = "kho-moc-fs-upload-v2:";

function emptyState(): FsUploadState {
  return { inspectionPhotos: [], contractFile: null };
}

export function readFsUploadState(appointmentId: string): FsUploadState {
  try {
    const raw = window.sessionStorage.getItem(`${PREFIX}${appointmentId}`);
    if (!raw) return emptyState();
    const parsed = JSON.parse(raw) as Partial<FsUploadState>;
    return {
      inspectionPhotos: Array.isArray(parsed.inspectionPhotos) ? parsed.inspectionPhotos : [],
      contractFile: parsed.contractFile ?? null,
    };
  } catch {
    return emptyState();
  }
}

export function writeFsUploadState(appointmentId: string, state: FsUploadState): void {
  try {
    window.sessionStorage.setItem(`${PREFIX}${appointmentId}`, JSON.stringify(state));
  } catch {
    // sessionStorage unavailable — the upload still works in this tab.
  }
}
