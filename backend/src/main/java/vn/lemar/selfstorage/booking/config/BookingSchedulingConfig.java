package vn.lemar.selfstorage.booking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bật scheduler cho job sửa state booking từ giao dịch thành công. */
@Configuration
@EnableScheduling
public class BookingSchedulingConfig {
}
