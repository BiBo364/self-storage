-- Theo dõi upload theo batch để dọn asset chưa attach khi FE bấm Hủy.
-- Upload tạo row Uploading trước khi gọi Cloudinary; đổi Pending sau khi lưu xong.
-- Delete batch mark Canceled + xoá asset Pending; upload completion thấy Canceled thì xoá asset.

CREATE TABLE media_uploads (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT NOT NULL REFERENCES accounts (id),
    batch_id    UUID NOT NULL,
    public_id   VARCHAR(255),
    url         TEXT,
    bytes       BIGINT,
    format      VARCHAR(20),
    status      VARCHAR(20) NOT NULL
                CHECK (status IN ('Uploading', 'Pending', 'Attached', 'Canceled', 'Deleted', 'Failed')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_media_uploads_account_batch_status
    ON media_uploads (account_id, batch_id, status);

CREATE UNIQUE INDEX uq_media_uploads_public_id
    ON media_uploads (public_id)
    WHERE public_id IS NOT NULL;
