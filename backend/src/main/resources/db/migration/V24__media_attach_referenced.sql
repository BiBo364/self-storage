-- Attach phía server: từ nay flow lưu URL ảnh/scan thì upload chuyển Attached trong cùng transaction. Upload Pending
-- cũ đang được entity dùng (FE chưa gọi /attach hoặc gọi lỗi) chuyển Attached luôn để cron dọn upload bỏ ngang không
-- xoá ảnh làm bằng chứng.
UPDATE media_uploads m
SET status = 'Attached', updated_at = now()
WHERE m.status = 'Pending'
  AND m.url IS NOT NULL
  AND (EXISTS (SELECT 1 FROM handover_records h WHERE m.url = ANY (h.inspection_photos))
       OR EXISTS (SELECT 1 FROM checkout_records c WHERE m.url = ANY (c.photos))
       OR EXISTS (SELECT 1 FROM support_requests s WHERE m.url = ANY (s.photos))
       OR EXISTS (SELECT 1 FROM rental_contracts r WHERE r.pdf_url = m.url));
