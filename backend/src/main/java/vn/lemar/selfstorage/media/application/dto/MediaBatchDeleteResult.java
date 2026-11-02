package vn.lemar.selfstorage.media.application.dto;

/** Kết quả dọn một batch: số asset đã xoá/cancel, số asset bỏ qua vì đã attach. */
public record MediaBatchDeleteResult(int deleted, int skipped) {}
