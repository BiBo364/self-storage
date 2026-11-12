-- Ảnh giấy tờ tuỳ thân (CCCD/hộ chiếu) FS chụp ở bước xác minh danh tính. Xác minh đạt bắt buộc ≥ 1 ảnh (kiểm ở
-- HandoverWorkflowService); bản ghi cũ để NULL.
ALTER TABLE handover_records ADD COLUMN identity_photos TEXT[];
