package vn.lemar.selfstorage.payment.application;

import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Timeout cho HTTP gọi cổng thanh toán. Tạo link / hỏi / huỷ phiên chạy trong transaction đang khoá đơn, hoá đơn
 * hoặc giao dịch — cổng treo không có timeout thì khoá bị giữ mãi, chặn cron và webhook của đơn đó.
 */
final class GatewayHttp {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private GatewayHttp() {
    }

    static ClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
