package vn.lemar.selfstorage.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.PaymentService.NotificationResult;
import vn.lemar.selfstorage.payment.controller.PayOsWebhookController;

/** Map kết quả xử lý webhook payOS sang HTTP: chữ ký / số tiền sai → 400, lỗi bất ngờ → 500, còn lại 200. */
class PayOsWebhookControllerTest {

    private final PaymentService paymentService = mock(PaymentService.class);
    private final PayOsWebhookController controller = new PayOsWebhookController(paymentService);

    @Test
    void acceptedAndRepeatedAndUnknownAre200() {
        for (NotificationResult result : new NotificationResult[] {NotificationResult.ACCEPTED,
            NotificationResult.ALREADY_SETTLED, NotificationResult.NOT_FOUND}) {
            when(paymentService.handleNotification("{}")).thenReturn(result);
            ResponseEntity<Map<String, Object>> response = controller.webhook("{}");
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("success", true).containsEntry("result", result.name());
        }
    }

    @Test
    void invalidSignatureOrAmountIs400() {
        when(paymentService.handleNotification("x")).thenReturn(NotificationResult.INVALID_SIGNATURE);
        assertThat(controller.webhook("x").getStatusCode().value()).isEqualTo(400);
        when(paymentService.handleNotification("x")).thenReturn(NotificationResult.INVALID_AMOUNT);
        ResponseEntity<Map<String, Object>> response = controller.webhook("x");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("success", false);
    }

    @Test
    void unexpectedErrorIs500() {
        when(paymentService.handleNotification("boom")).thenThrow(new IllegalStateException("db down"));
        ResponseEntity<Map<String, Object>> response = controller.webhook("boom");
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("result", "ERROR");
    }
}
