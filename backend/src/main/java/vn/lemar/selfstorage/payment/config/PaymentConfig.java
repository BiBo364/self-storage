package vn.lemar.selfstorage.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Đăng ký cấu hình của module payment.
 *
 * <p>Khai báo ở đây thay vì bật {@code @ConfigurationPropertiesScan} trên lớp khởi động,
 * để module tự quản cấu hình của mình và không phải sửa file dùng chung.
 */
@Configuration
@EnableConfigurationProperties({VnPayProperties.class, PayOsProperties.class})
public class PaymentConfig {}
