package vn.lemar.selfstorage.media;

import java.util.Collection;
import java.util.Set;

/**
 * Tra cứu chỉ đọc: URL module khác nhận từ client có đúng là file account đó đã upload qua
 * {@code POST /api/media/uploads} hay không. Không đổi trạng thái upload.
 */
public interface MediaUrlLookup {

    /**
     * Các URL trong {@code urls} <b>không</b> khớp upload còn dùng được ({@code Pending}/{@code Attached}) của
     * {@code accountId}; rỗng = mọi URL hợp lệ. Upload đã huỷ/xoá không tính.
     */
    Set<String> notUploadedBy(long accountId, Collection<String> urls);
}
