package vn.lemar.selfstorage.booking.application;

/**
 * Kết quả khách bấm "Đồng ý".
 *
 * <p>Nhánh khoang hết {@code Available} không ném exception: spec 1.3 yêu cầu vẫn lưu proposal
 * {@code Canceled} + báo FM, nên transaction phải commit; controller tự đổi
 * {@link Outcome#UNIT_UNAVAILABLE} thành 409 cho khách.
 *
 * @param invoice hoá đơn cọc vừa tạo; {@code null} khi {@link Outcome#UNIT_UNAVAILABLE}
 */
public record AgreeResult(Outcome outcome, ProposalView proposal, InvoiceView invoice) {

    public enum Outcome {
        AGREED,
        UNIT_UNAVAILABLE
    }
}
