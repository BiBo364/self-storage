import storageDriveUp from "../../assets/photos/storage-drive-up.webp";
import storageRow from "../../assets/photos/storage-row.webp";
import storageYellowDoors from "../../assets/photos/storage-yellow-doors.webp";

// Ảnh Unsplash (Unsplash License — dùng miễn phí, không bắt buộc ghi nguồn), đã cắt khung ngang và nén WebP:
// - storage-yellow-doors: Aga Adamek — https://unsplash.com/photos/9OfBsfty2x4
// - storage-drive-up: Adam Winger — https://unsplash.com/photos/-BQBmDlDE70
// - storage-row: Adam Winger — https://unsplash.com/photos/Pf91TwFrn-o
export const STORAGE_PHOTOS = {
  yellowDoors: storageYellowDoors,
  driveUp: storageDriveUp,
  row: storageRow,
} as const;

/**
 * Ảnh nền trang trí phủ kín phần tử cha (cha cần `relative overflow-hidden`, nội dung cần `relative` để nằm
 * trên). Lớp phủ trong `.photo-backdrop` lấy màu nền theo theme nên chữ vẫn đọc được ở cả sáng lẫn tối.
 */
export function PhotoBackdrop({ src, position = "center" }: { src: string; position?: string }) {
  return (
    <div className="photo-backdrop" aria-hidden="true">
      <img src={src} alt="" decoding="async" style={{ objectPosition: position }} />
    </div>
  );
}
