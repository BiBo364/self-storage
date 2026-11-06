package vn.lemar.selfstorage.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.payment.application.VnPayGateway;
import vn.lemar.selfstorage.payment.application.dto.PaymentRequest;
import vn.lemar.selfstorage.payment.config.VnPayProperties;

/**
 * Bộ tham số dùng ở đây lấy nguyên hình dạng từ một giao dịch sandbox thật đã chạy thành công
 * (thẻ NCB, `vnp_ResponseCode = 00`), chỉ thay `vnp_TmnCode` và khóa bí mật bằng giá trị giả.
 * Chữ ký kỳ vọng được tính sẵn bằng khóa giả đó, nên test không cần credential thật.
 */
class VnPayGatewayTest {

    private static final String FAKE_SECRET = "TESTSECRET0123456789TESTSECRET01";

    private static final String EXPECTED_SIGNATURE =
            "dec99829817278398ced094223d82d577d4b74fb404746804b98d57a5edc5798"
                    + "3c7ece45962c5eb4e5e9e67646585843f8287895c0ed0ff159145a5714d6017e";

    private final VnPayGateway gateway = new VnPayGateway(new VnPayProperties(
            "TESTTMN1",
            FAKE_SECRET,
            "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
            "http://localhost:8080/api/payments/vnpay/return"));

    private Map<String, String> callbackParams() {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_Amount", "1000000");
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_BankTranNo", "VNP15690354");
        params.put("vnp_CardType", "ATM");
        params.put("vnp_OrderInfo", "Thanh toan thu don 160932558");
        params.put("vnp_PayDate", "20260929161520");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TmnCode", "TESTTMN1");
        params.put("vnp_TransactionNo", "15690354");
        params.put("vnp_TransactionStatus", "00");
        params.put("vnp_TxnRef", "160932558");
        params.put("vnp_SecureHash", EXPECTED_SIGNATURE);
        return params;
    }

    @Test
    void acceptsValidGatewaySignature() {
        assertThat(gateway.verifySignature(callbackParams())).isTrue();
    }

    @Test
    void rejectsTamperedDataWithOriginalSignature() {
        Map<String, String> tampered = callbackParams();
        tampered.put("vnp_Amount", "9900000");

        assertThat(gateway.verifySignature(tampered)).isFalse();
    }

    @Test
    void rejectsWhenSignatureMissing() {
        Map<String, String> params = callbackParams();
        params.remove("vnp_SecureHash");

        assertThat(gateway.verifySignature(params)).isFalse();
    }

    @Test
    void ignoresSecureHashTypeWhenRecomputingSignature() {
        Map<String, String> params = callbackParams();
        params.put("vnp_SecureHashType", "HmacSHA512");

        assertThat(gateway.verifySignature(params)).isTrue();
    }

    @Test
    void paymentUrlMultipliesAmountBy100() {
        String url = gateway.buildPaymentUrl(
                new PaymentRequest("TXN001", new BigDecimal("10000"), "Thanh toan don TXN001", "127.0.0.1"));

        assertThat(queryOf(url)).containsEntry("vnp_Amount", "1000000");
    }

    @Test
    void orderInfoIsAsciiFoldedBeforeSigning() {
        String url = gateway.buildPaymentUrl(new PaymentRequest(
                "TXN003", new BigDecimal("500000"), "Đặt cọc khoang chứa A-01", "127.0.0.1"));

        Map<String, String> params = queryOf(url);
        assertThat(params).containsEntry("vnp_OrderInfo", "Dat coc khoang chua A-01");
        assertThat(gateway.verifySignature(params)).isTrue();
    }

    @Test
    void rebuildingKeepsTheOriginalCreateAndExpireTime() {
        Instant createdAt = Instant.parse("2026-10-28T12:30:00Z");
        String url = gateway.buildPaymentUrl(new PaymentRequest(
                "TXN004", new BigDecimal("10000"), "Thanh toan", "127.0.0.1"), createdAt);

        Map<String, String> params = queryOf(url);
        assertThat(params)
                .containsEntry("vnp_CreateDate", "20261028193000")
                .containsEntry("vnp_ExpireDate", "20261028194500");
        assertThat(gateway.verifySignature(params)).isTrue();
    }

    @Test
    void paymentUrlSelfSignedVerifiesBack() {
        String url = gateway.buildPaymentUrl(
                new PaymentRequest("TXN002", new BigDecimal("250000"), "Thanh toan don TXN002", "127.0.0.1"));

        Map<String, String> params = queryOf(url);
        assertThat(params).containsKey("vnp_SecureHash");
        assertThat(gateway.verifySignature(params)).isTrue();
    }

    private Map<String, String> queryOf(String url) {
        Map<String, String> params = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int idx = pair.indexOf('=');
            params.put(pair.substring(0, idx),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.US_ASCII));
        }
        return params;
    }
}
