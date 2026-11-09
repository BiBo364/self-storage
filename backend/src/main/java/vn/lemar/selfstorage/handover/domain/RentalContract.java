package vn.lemar.selfstorage.handover.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Hợp đồng thuê ({@code specs/db-table-draft.md} › RentalContract).
 *
 * <p>Flow 2.3 sinh hợp đồng {@code Draft} sau khi khách đồng ý hiện trạng khoang; bản này chỉ map
 * bảng V1 và cho phép huỷ hợp đồng chưa bàn giao. Bảng không có {@code created_at}/{@code updated_at}
 * nên entity không map hai cột đó.
 */
@Entity
@Table(name = "rental_contracts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "terms_version", nullable = false, length = 50)
    private String termsVersion;

    @Column(name = "monthly_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(name = "deposit_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal depositAmount;

    @Column(nullable = false)
    private Integer period;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "start_date_override_requested")
    private LocalDate startDateOverrideRequested;

    @Column(name = "start_date_override_reason")
    private String startDateOverrideReason;

    /** Pending / Approved / Rejected — CHECK V1, chưa có behavior ở Flow 2.3. */
    @Column(name = "start_date_override_status", length = 20)
    private String startDateOverrideStatus;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "signed_at")
    private Instant signedAt;

    /** URL ảnh chữ ký — MVP ký giấy nên để trống. */
    private String signature;

    @Column(name = "document_url")
    private String documentUrl;

    @Column(name = "pdf_url")
    private String pdfUrl;

    @Convert(converter = RentalContractStatusConverter.class)
    @Column(nullable = false, length = 20)
    private RentalContractStatus status;

    /** Bản hợp đồng chốt lúc ký — in lại dùng bản này, không render lại từ dữ liệu hiện tại. */
    @Column(name = "document_html")
    private String documentHtml;

    /** Quá hạn kéo dài: mã khoá số đã tạm khoá từ thời điểm này; {@code null} = không bị khoá. */
    @Column(name = "access_suspended_at")
    private Instant accessSuspendedAt;

    public static final String OVERRIDE_PENDING = "Pending";
    public static final String OVERRIDE_APPROVED = "Approved";
    public static final String OVERRIDE_REJECTED = "Rejected";

    /**
     * Spec 2.3 giai đoạn 1: hợp đồng {@code Draft} sinh từ mẫu đang hiệu lực khi khách đồng ý hiện trạng.
     * {@code end_date} = ngày cuối của kỳ thuê ({@code start_date + period tháng - 1 ngày}).
     */
    public static RentalContract draft(Long orderId, Long customerId, Long unitId, String code, String termsVersion,
                                       BigDecimal monthlyPrice, BigDecimal depositAmount, int period,
                                       LocalDate startDate) {
        RentalContract contract = new RentalContract();
        contract.orderId = orderId;
        contract.customerId = customerId;
        contract.unitId = unitId;
        contract.code = code;
        contract.termsVersion = termsVersion;
        contract.monthlyPrice = monthlyPrice;
        contract.depositAmount = depositAmount;
        contract.period = period;
        contract.setStartDate(startDate);
        contract.status = RentalContractStatus.DRAFT;
        return contract;
    }

    /** Link bản hợp đồng render từ mẫu (chưa ký). */
    public void attachDocument(String documentUrl) {
        this.documentUrl = documentUrl;
    }

    public boolean hasPendingOverride() {
        return OVERRIDE_PENDING.equals(startDateOverrideStatus);
    }

    /** Spec 2.3: FS gửi đề nghị đổi mốc tính tiền trên hợp đồng {@code Draft}; khoá bước ký tới khi FM xử lý. */
    public void requestStartDateOverride(LocalDate requested, String reason) {
        requireStatus(RentalContractStatus.DRAFT);
        if (hasPendingOverride()) {
            throw new IllegalStateException("Hợp đồng " + id + " đang có đề nghị đổi mốc chờ duyệt");
        }
        this.startDateOverrideRequested = requested;
        this.startDateOverrideReason = reason;
        this.startDateOverrideStatus = OVERRIDE_PENDING;
    }

    /** FM duyệt: dùng ngày đề nghị cho {@code start_date}, mở lại bước ký. */
    public void approveStartDateOverride() {
        requirePendingOverride();
        setStartDate(startDateOverrideRequested);
        this.startDateOverrideStatus = OVERRIDE_APPROVED;
    }

    /** FM từ chối: giữ ngày theo chính sách, mở lại bước ký. */
    public void rejectStartDateOverride() {
        requirePendingOverride();
        this.startDateOverrideStatus = OVERRIDE_REJECTED;
    }

    /** Spec 2.3: FS upload bản scan đã ký — {@code Draft -> Signed}. */
    public void sign(String pdfUrl, Instant now) {
        requireStatus(RentalContractStatus.DRAFT);
        if (hasPendingOverride()) {
            throw new IllegalStateException("Hợp đồng " + id + " còn đề nghị đổi mốc chưa duyệt");
        }
        this.pdfUrl = pdfUrl;
        this.signedAt = now;
        this.status = RentalContractStatus.SIGNED;
    }

    /** Đã chốt bản hợp đồng lúc ký — in lại trả đúng bản này. */
    public boolean documentFrozen() {
        return documentHtml != null;
    }

    /**
     * Chốt bản hợp đồng đã ký (HTML render đúng lúc ký) — gọi sau {@link #sign}, chỉ một lần; sau đó dữ liệu
     * hiện tại (giá, hiện trạng, gia hạn…) đổi cũng không làm đổi bản đã ký.
     */
    public void freezeDocument(String html) {
        requireStatus(RentalContractStatus.SIGNED);
        if (documentFrozen()) {
            throw new IllegalStateException("Hợp đồng " + id + " đã chốt bản ký");
        }
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("Bản hợp đồng chốt không được rỗng");
        }
        this.documentHtml = html;
    }

    /** Spec 2.4: bàn giao hoàn tất — {@code Signed -> Active}. */
    public void activate() {
        requireStatus(RentalContractStatus.SIGNED);
        this.status = RentalContractStatus.ACTIVE;
    }

    /** Gia hạn hợp đồng đang thuê: kỳ thuê và {@code end_date} cộng thêm {@code months}. */
    /** Tạm khoá truy cập do quá hạn; {@code false} nếu đã khoá rồi. */
    public boolean suspendAccess(Instant now) {
        requireStatus(RentalContractStatus.ACTIVE);
        if (accessSuspendedAt != null) {
            return false;
        }
        this.accessSuspendedAt = now;
        return true;
    }

    /** Bỏ tạm khoá; {@code false} nếu đang không khoá. */
    public boolean restoreAccess() {
        if (accessSuspendedAt == null) {
            return false;
        }
        this.accessSuspendedAt = null;
        return true;
    }

    public void extend(int months) {
        requireStatus(RentalContractStatus.ACTIVE);
        if (months <= 0) {
            throw new IllegalArgumentException("Số tháng gia hạn phải > 0");
        }
        this.period = period + months;
        // Tính lại từ start_date — cộng tháng vào end_date cuối tháng bị cắt ngày (31/1 → 28/2 → 28/3) và trôi dần.
        this.endDate = startDate.plusMonths(period).minusDays(1);
    }

    /** Trả kho hoàn tất: {@code Active -> Ended}. */
    public void end() {
        requireStatus(RentalContractStatus.ACTIVE);
        this.status = RentalContractStatus.ENDED;
    }

    private void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
        this.endDate = startDate.plusMonths(period).minusDays(1);
    }

    private void requireStatus(RentalContractStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Hợp đồng " + id + " không ở " + expected.dbValue() + ": "
                    + status.dbValue());
        }
    }

    private void requirePendingOverride() {
        if (!hasPendingOverride()) {
            throw new IllegalStateException("Hợp đồng " + id + " không có đề nghị đổi mốc chờ duyệt");
        }
    }

    /** Huỷ hợp đồng chưa bàn giao ({@code Draft}/{@code Signed}); {@code Active}/{@code Ended} giữ nguyên. */
    public void cancel() {
        if (!status.isCancelable()) {
            throw new IllegalStateException("Hợp đồng " + id + " không huỷ được từ trạng thái " + status);
        }
        this.status = RentalContractStatus.CANCELED;
    }
}
