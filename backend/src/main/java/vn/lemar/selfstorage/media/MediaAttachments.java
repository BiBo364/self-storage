package vn.lemar.selfstorage.media;

import java.util.Collection;

/**
 * Module khác gắn URL đã upload vào entity của mình (ảnh hiện trạng, scan hợp đồng, ảnh hỗ trợ) → upload
 * {@code Pending} của account đó chuyển {@code Attached}, cùng transaction với thao tác lưu. Asset {@code Attached}
 * không bị Hủy batch hay cron dọn upload bỏ ngang xoá.
 */
public interface MediaAttachments {

    /**
     * Attach các upload {@code Pending} của {@code accountId} có URL trong {@code urls}; URL không khớp (của account
     * khác, đã attach, đã huỷ) bỏ qua. Trả số upload vừa attach.
     */
    int attach(long accountId, Collection<String> urls);
}
