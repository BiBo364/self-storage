package vn.lemar.selfstorage.booking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bật scheduler cho job booking; một instance chạy job. */
@Configuration
@EnableScheduling
public class BookingSchedulingConfig {
}
