package vn.lemar.selfstorage.payment.application.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Kết quả {@code querydr} — hỏi cổng trạng thái thật của một giao dịch (server-to-server, có chữ ký).
 *
 * @param responseCode       {@code vnp_ResponseCode} của lệnh truy vấn ({@code 00} = truy vấn thành công,
 *                           {@code 91} = cổng không có giao dịch này)
 * @param transactionStatus  {@code vnp_TransactionStatus} ({@code 00} = đã thanh toán, {@code 01} = chưa hoàn tất)
 * @param transactionNo      mã giao dịch phía cổng
 * @param amount             số tiền VND (đã chia 100); {@code null} nếu cổng không trả
 * @param payDate            {@code vnp_PayDate} thô ({@code yyyyMMddHHmmss}, GMT+7)
 * @param signatureValid     chữ ký response hợp lệ
 * @param payload            toàn bộ response để lưu đối soát
 */
public record GatewayQueryResult(String responseCode, String transactionStatus, String transactionNo,
                                 BigDecimal amount, String payDate, boolean signatureValid,
                                 Map<String, String> payload) {

    private static final String OK = "00";
    private static final String PROCESSING = "01";
    private static final String NOT_FOUND = "91";

    /** Cổng xác nhận đã thu tiền. */
    public boolean paid() {
        return signatureValid && OK.equals(responseCode) && OK.equals(transactionStatus);
    }

    /** Cổng không có giao dịch — khách chưa từng hoàn tất bước nhập thẻ. */
    public boolean notFound() {
        return NOT_FOUND.equals(responseCode);
    }

    /** Cổng có giao dịch nhưng ở trạng thái lỗi/huỷ (không phải {@code 00} đã trả, {@code 01} đang xử lý). */
    public boolean failed() {
        return signatureValid && OK.equals(responseCode) && transactionStatus != null
                && !OK.equals(transactionStatus) && !PROCESSING.equals(transactionStatus);
    }
}
