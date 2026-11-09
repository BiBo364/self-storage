package vn.lemar.selfstorage.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.lemar.selfstorage.payment.application.dto.GatewayNotification;
import vn.lemar.selfstorage.payment.application.dto.GatewayQueryResult;
import vn.lemar.selfstorage.payment.application.dto.PaymentRequest;
import vn.lemar.selfstorage.payment.config.PayOsProperties;

/** Adapter payOS: ký tạo link, đọc tra cứu / huỷ, kiểm chữ ký webhook — không gọi mạng (MockRestServiceServer). */
class PayOsGatewayTest {

    private static final String BASE = "https://api-merchant.payos.vn";
    private static final PayOsProperties PROPS = new PayOsProperties("client-1", "key-1",
            "checksum-key-for-test", BASE, "http://fe/payments/result", "http://fe/payments/result");

    private MockRestServiceServer server;
    private PayOsGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new PayOsGateway(PROPS, builder);
    }

    @Test
    void createLinkSignsBodyAndReturnsCheckoutUrl() {
        String expected = gateway.sign("amount=500000&cancelUrl=http://fe/payments/result&description=TT hoa don 12"
                + "&orderCode=123456&returnUrl=http://fe/payments/result");
        server.expect(requestTo(BASE + "/v2/payment-requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-client-id", "client-1"))
                .andExpect(header("x-api-key", "key-1"))
                .andExpect(jsonPath("$.orderCode").value(123456))
                .andExpect(jsonPath("$.amount").value(500000))
                .andExpect(jsonPath("$.signature").value(expected))
                .andRespond(withSuccess("{\"code\":\"00\",\"data\":{\"checkoutUrl\":\"https://pay.payos.vn/web/x\"}}",
                        MediaType.APPLICATION_JSON));

        String url = gateway.buildPaymentUrl(new PaymentRequest("123456", new BigDecimal("500000.00"),
                "TT hoa don 12", "127.0.0.1", Instant.parse("2026-11-06T10:00:00Z")));

        assertThat(url).isEqualTo("https://pay.payos.vn/web/x");
        server.verify();
    }

    @Test
    void rejectedCreateLinkThrows() {
        server.expect(requestTo(BASE + "/v2/payment-requests"))
                .andRespond(withSuccess("{\"code\":\"231\",\"desc\":\"Đơn thanh toán đã tồn tại\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.buildPaymentUrl(new PaymentRequest("1", BigDecimal.TEN, "x", "ip")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("đã tồn tại");
    }

    @Test
    void queryMapsPaidCancelledPendingAndMissing() {
        server.expect(requestTo(BASE + "/v2/payment-requests/1"))
                .andRespond(withSuccess("{\"code\":\"00\",\"data\":{\"id\":\"L1\",\"orderCode\":1,\"amount\":500000,"
                        + "\"amountPaid\":500000,\"status\":\"PAID\",\"transactions\":[{\"reference\":\"FT1\","
                        + "\"transactionDateTime\":\"2026-11-06T21:16:56+07:00\"}]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v2/payment-requests/2"))
                .andRespond(withSuccess("{\"code\":\"00\",\"data\":{\"id\":\"L2\",\"amount\":10,"
                        + "\"status\":\"CANCELLED\",\"transactions\":[]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v2/payment-requests/3"))
                .andRespond(withSuccess("{\"code\":\"00\",\"data\":{\"id\":\"L3\",\"amount\":10,"
                        + "\"status\":\"PENDING\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v2/payment-requests/4")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/v2/payment-requests/5"))
                .andRespond(withSuccess("{\"code\":\"101\",\"desc\":\"không tồn tại\"}", MediaType.APPLICATION_JSON));

        GatewayQueryResult paid = gateway.query("1", Instant.now(), null, null);
        assertThat(paid.paid()).isTrue();
        assertThat(paid.amount()).isEqualByComparingTo("500000");
        assertThat(paid.transactionNo()).isEqualTo("FT1");
        assertThat(paid.payDate()).isEqualTo("20261106211656");
        assertThat(gateway.query("2", Instant.now(), null, null).failed()).isTrue();
        GatewayQueryResult pending = gateway.query("3", Instant.now(), null, null);
        assertThat(pending.paid()).isFalse();
        assertThat(pending.failed()).isFalse();
        assertThat(gateway.query("4", Instant.now(), null, null).notFound()).isTrue();
        assertThat(gateway.query("5", Instant.now(), null, null).notFound()).isTrue();
    }

    @Test
    void cancelReportsGatewayOutcome() {
        server.expect(requestTo(BASE + "/v2/payment-requests/1/cancel"))
                .andExpect(jsonPath("$.cancellationReason").value("Don thue da bi huy"))
                .andRespond(withSuccess("{\"code\":\"00\",\"data\":{\"status\":\"CANCELLED\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v2/payment-requests/2/cancel"))
                .andRespond(withSuccess("{\"code\":\"20\",\"desc\":\"đã thanh toán\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v2/payment-requests/3/cancel")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(gateway.cancel("1", "Don thue da bi huy")).isTrue();
        assertThat(gateway.cancel("2", null)).isFalse();
        assertThat(gateway.cancel("3", "x")).isFalse();
    }

    @Test
    void webhookSignatureIsVerifiedOnSortedData() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode data = mapper.createObjectNode();
        data.put("orderCode", 123456);
        data.put("amount", 500000);
        data.put("description", "TT hoa don 12");
        data.put("reference", "FT123");
        data.put("transactionDateTime", "2026-11-06 21:20:00");
        data.put("code", "00");
        data.putNull("virtualAccountName");
        ObjectNode root = mapper.createObjectNode();
        root.put("code", "00");
        root.set("data", data);
        root.put("signature", gateway.sign(gateway.dataSignatureString(data)));

        GatewayNotification ok = gateway.parseNotification(root.toString()).orElseThrow();
        assertThat(ok.txnRef()).isEqualTo("123456");
        assertThat(ok.success()).isTrue();
        assertThat(ok.amount()).isEqualByComparingTo("500000");
        assertThat(ok.paidAt()).isEqualTo(Instant.parse("2026-11-06T14:20:00Z"));

        root.put("signature", "tampered");
        assertThat(gateway.parseNotification(root.toString())).isEmpty();
        assertThat(gateway.parseNotification("not json")).isEmpty();
        assertThat(gateway.verifySignature(Map.of("a", "1", "signature", gateway.sign("a=1")))).isTrue();
        assertThat(gateway.verifySignature(Map.of("a", "1"))).isFalse();
    }

    @Test
    void txnRefIsNumericAndDescriptionIsAsciiWithin25Chars() {
        assertThat(gateway.newTxnRef(7L)).matches("\\d{15,16}");
        assertThat(PayOsGateway.description("Thanh toán hoá đơn đặt cọc số 1234567890"))
                .hasSizeLessThanOrEqualTo(25)
                .matches("[A-Za-z0-9 ]+")
                .startsWith("Thanh toan hoa don");
        assertThat(PayOsGateway.description(null)).isEqualTo("Thanh toan");
    }

    @Test
    void missingCredentialsFailFast() {
        assertThatThrownBy(() -> new PayOsGateway(new PayOsProperties("", "k", "c", BASE, "r", "c"),
                RestClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYOS_CLIENT_ID");
    }
}
