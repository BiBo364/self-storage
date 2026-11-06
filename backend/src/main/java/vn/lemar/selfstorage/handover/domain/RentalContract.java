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

    /** Huỷ hợp đồng chưa bàn giao ({@code Draft}/{@code Signed}); {@code Active}/{@code Ended} giữ nguyên. */
    public void cancel() {
        if (!status.isCancelable()) {
            throw new IllegalStateException("Hợp đồng " + id + " không huỷ được từ trạng thái " + status);
        }
        this.status = RentalContractStatus.CANCELED;
    }
}
