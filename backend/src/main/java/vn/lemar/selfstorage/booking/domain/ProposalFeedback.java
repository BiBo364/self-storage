package vn.lemar.selfstorage.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Đề xuất khoang và phản hồi của khách ({@code specs/db-table-draft.md} › ProposalFeedback).
 *
 * <p>Mỗi lần đề xuất lại tạo bản ghi mới, bản ghi cũ giữ nguyên làm lịch sử. Khách chỉ đồng ý/từ chối
 * được proposal {@code Pending} — caller kiểm {@link #isPending()} trước khi gọi.
 * Cột {@code expires_at} không map: spec NOTES 1.3 nói ProposalFeedback không đặt TTL.
 */
@Entity
@Table(name = "proposal_feedbacks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Convert(converter = ProposalStatusConverter.class)
    @Column(nullable = false, length = 20)
    private ProposalStatus status;

    @Column
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Lúc khách đồng ý — mốc mở lại cửa sổ đặt lịch check-in khi đơn đã cọc phải đổi khoang (V23). */
    @Column(name = "agreed_at")
    private Instant agreedAt;

    private ProposalFeedback(Long orderId, Long customerId, Long unitId) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.unitId = unitId;
        this.status = ProposalStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /** Đề xuất mới cho đơn — luôn bắt đầu ở {@code Pending}. */
    public static ProposalFeedback propose(RentalOrder order, Long unitId) {
        return new ProposalFeedback(order.getId(), order.getCustomerId(), unitId);
    }

    public boolean isPending() {
        return status == ProposalStatus.PENDING;
    }

    public void agree() {
        requirePending();
        this.status = ProposalStatus.AGREED;
        this.agreedAt = Instant.now();
    }

    public void reject(String rejectNote) {
        requirePending();
        this.status = ProposalStatus.REJECTED;
        this.note = rejectNote;
    }

    /** Huỷ được khi còn hiệu lực: {@code Pending} hoặc {@code Agreed} (Cancellation Cascade). */
    public boolean isCancelable() {
        return status == ProposalStatus.PENDING || status == ProposalStatus.AGREED;
    }

    /**
     * {@code Canceled} (thay {@code Expired} của spec) khi khoang hết {@code Available} lúc
     * khách đồng ý, hoặc khi đơn chạy Cancellation Cascade.
     */
    public void cancel() {
        if (!isCancelable()) {
            throw new IllegalStateException("Proposal " + id + " không huỷ được ở trạng thái " + status);
        }
        this.status = ProposalStatus.CANCELED;
    }

    private void requirePending() {
        if (!isPending()) {
            throw new IllegalStateException("Proposal " + id + " không còn Pending: " + status);
        }
    }
}
