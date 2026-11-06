package vn.lemar.selfstorage.payment.application;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache URL thanh toán theo invoice, TTL đúng bằng cửa sổ phiên VNPay.
 *
 * <p>URL là dữ liệu tạm: mất cache không làm sai trạng thái tiền, chỉ khiến khách không quay lại
 * được phiên cũ. Vì vậy mọi lỗi Redis đều bị nuốt và log — DB vẫn là nguồn của khóa/phiên.
 */
@Component
public class PaymentUrlCache {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentUrlCache.class);
    private static final String KEY_PREFIX = "payment:url:";

    private final StringRedisTemplate redis;

    public PaymentUrlCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Ghi URL của phiên đang mở; lỗi Redis không được làm hỏng việc mở phiên. */
    public void put(Long invoiceId, String paymentUrl) {
        put(invoiceId, paymentUrl, PaymentSession.WINDOW);
    }

    /** Ghi URL với TTL còn lại của phiên — dùng khi dựng lại URL từ transaction cũ. */
    public void put(Long invoiceId, String paymentUrl, Duration ttl) {
        if (paymentUrl == null || paymentUrl.isBlank()
                || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(key(invoiceId), paymentUrl, ttl);
        } catch (RuntimeException e) {
            LOG.warn("Không cache được URL thanh toán cho hóa đơn {}: {}", invoiceId, e.getMessage());
        }
    }

    /** URL còn hạn của phiên cũ, nếu cache còn. Giá trị rỗng/blank coi như mất cache. */
    public Optional<String> get(Long invoiceId) {
        try {
            String value = redis.opsForValue().get(key(invoiceId));
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (RuntimeException e) {
            LOG.warn("Không đọc được URL thanh toán cho hóa đơn {}: {}", invoiceId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String key(Long invoiceId) {
        return KEY_PREFIX + invoiceId;
    }
}
