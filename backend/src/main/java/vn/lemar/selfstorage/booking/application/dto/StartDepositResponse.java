package vn.lemar.selfstorage.booking.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** URL để FE chuyển hướng khách sang cổng VNPay. */
@Schema(description = "Kết quả mở phiên thanh toán cọc")
public record StartDepositResponse(
        @Schema(description = "URL cổng VNPay đã ký; FE redirect thẳng sang đây",
                example = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=1000000&...")
        String paymentUrl) {
}
