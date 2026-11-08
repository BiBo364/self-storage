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
 * Đơn thuê ({@code specs/db-table-draft.md} › RentalOrder).
 *
 * <p>Đơn do luồng convert tạo ({@link #open}); các thao tác còn lại phục vụ Flow 1.3. {@code unitId},
 * {@code customerId}, {@code requestId} để kiểu {@code Long} chứ không phải quan hệ JPA: bảng đích
 * thuộc module khác — ranh giới module cấm import {@code domain} của nhau, khoá ngoại vẫn do DB giữ.
 */
@Entity
@Table(name = "rental_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true)
    private Long requestId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "unit_id")
    private Long unitId;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Convert(converter = RentalOrderStatusConverter.class)
    @Column(nullable = false, length = 20)
    private RentalOrderStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /**
     * FM cần đề xuất lại dù đơn đã qua {@code Pending} (vd khách từ chối khoang tại check-in). Flow 2 bật/tắt cờ;
     * Flow 1.3 chỉ đọc trong điều kiện đề xuất lại.
     */
    @Column(name = "needs_reproposal", nullable = false)
    private boolean needsReproposal;

    /**
     * Spec 1.1/1.2: đơn convert từ request — {@code Pending}, chưa gán {@code unit_id} cho tới khi
     * proposal {@code Agreed}; {@code expires_at} set một lần lúc tạo.
     */
    public static RentalOrder open(Long requestId, Long customerId, Instant expiresAt) {
        RentalOrder order = new RentalOrder();
        order.requestId = requestId;
        order.customerId = customerId;
        order.status = RentalOrderStatus.PENDING;
        order.expiresAt = expiresAt;
        return order;
    }

    /**
     * FM được đề xuất lại khi đơn chưa đóng và đang {@code Pending} hoặc được đánh dấu {@code needs_reproposal}.
     * Điều kiện "không có proposal Pending" do service kiểm (cần truy vấn proposal).
     */
    public boolean allowsReproposal() {
        return !status.isClosed() && (status == RentalOrderStatus.PENDING || needsReproposal);
    }

    /** FM đã tạo proposal mới thay cho khoang bị từ chối — cờ chỉ dùng cho gate đề xuất lại. */
    public void clearNeedsReproposal() {
        this.needsReproposal = false;
    }

    /** Khách từ chối/không nhận được khoang trên đơn đã cọc — FM cần đề xuất lại. */
    public void requireReproposal() {
        this.needsReproposal = true;
    }

    /**
     * Đơn đang thực sự giữ khoang {@code unit_id}: khách đã đặt cọc và đơn chưa đóng. Đơn {@code Pending} chỉ mới
     * gán khoang khi đồng ý đề xuất (chưa cọc) nên không giữ — nhiều đơn có thể cùng trỏ một khoang.
     */
    public boolean holdsUnit() {
        return status == RentalOrderStatus.DEPOSITED || status == RentalOrderStatus.SCHEDULED
                || status == RentalOrderStatus.IN_PROGRESS;
    }

    /** Spec 1.3: {@code RentalOrder.unit_id} = {@code unit_id} của proposal {@code Agreed} mới nhất. */
    /**
     * Đã thu cọc (Flow 1.4). Chỉ đổi trạng thái: `expires_at` là hạn hoàn thành đơn,
     * `db-table-draft.md` › RentalOrder ghi "set một lần khi tạo đơn", và hạn giữ kho là phần
     * còn lại của chính hạn đó — cọc không gia hạn thêm.
     */
    public void markDeposited() {
        this.status = RentalOrderStatus.DEPOSITED;
    }

    public void assignUnit(Long agreedUnitId) {
        this.unitId = agreedUnitId;
    }

    /** Đơn đã có lịch check-in và đang chờ FM phân công FS. */
    public boolean isScheduled() {
        return status == RentalOrderStatus.SCHEDULED;
    }

    /** Spec 1.5: FM phân công FS cho lịch check-in → {@code Scheduled -> InProgress}. */
    public void startProgress() {
        if (status != RentalOrderStatus.SCHEDULED) {
            throw new IllegalStateException("Đơn " + id + " không ở Scheduled: " + status);
        }
        this.status = RentalOrderStatus.IN_PROGRESS;
    }

    /**
     * Flow 2 cron: phiên bàn giao quá hạn nhưng đơn còn hạn giữ kho — trả đơn về
     * {@code Deposited} để khách đặt lịch check-in mới.
     */
    public void returnToDeposited() {
        if (status != RentalOrderStatus.IN_PROGRESS) {
            throw new IllegalStateException("Đơn " + id + " không ở InProgress: " + status);
        }
        this.status = RentalOrderStatus.DEPOSITED;
    }

    /** Cancellation Cascade bước 1: {@code status -> Canceled}, ghi {@code cancel_reason}. */
    public void cancel(String reason) {
        if (status.isClosed()) {
            throw new IllegalStateException("Đơn " + id + " đã đóng: " + status);
        }
        this.status = RentalOrderStatus.CANCELED;
        this.cancelReason = reason;
    }
}
