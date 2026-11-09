package vn.lemar.selfstorage.booking.application;

/**
 * Kết quả convert một {@code RentalRequest} sang đơn + proposal.
 *
 * @param orderId    đơn vừa tạo — chỉ khác null khi {@link Outcome#CONVERTED}
 * @param proposalId proposal vừa tạo — chỉ khác null khi {@link Outcome#CONVERTED}
 */
public record ConversionResult(Long requestId, Outcome outcome, Long orderId, Long proposalId) {

    public enum Outcome {
        /** Đã tạo {@code RentalOrder(Pending)} + {@code ProposalFeedback(Pending)}, request → {@code Converted}. */
        CONVERTED,
        /**
         * Email chưa có account đã xác minh — request giữ {@code Approved}, chờ claim sau xác minh
         * (spec 1.1 "email chưa có tài khoản", 1.2 Case A).
         */
        NO_VERIFIED_ACCOUNT,
        /** Request không còn {@code Approved} hoặc đã quá {@code expires_at} — không claim. */
        NOT_CONVERTIBLE,
        /**
         * Account đã có đơn/proposal đang hoạt động cho cùng khoang — request giữ nguyên {@code Approved}
         * để FM xử lý (spec 1.2 Case A); quá hạn thì cron Flow 1 chuyển {@code Expired}.
         */
        DUPLICATE_UNIT
    }

    static ConversionResult converted(Long requestId, Long orderId, Long proposalId) {
        return new ConversionResult(requestId, Outcome.CONVERTED, orderId, proposalId);
    }

    static ConversionResult skipped(Long requestId, Outcome outcome) {
        return new ConversionResult(requestId, outcome, null, null);
    }

    public boolean isConverted() {
        return outcome == Outcome.CONVERTED;
    }
}
