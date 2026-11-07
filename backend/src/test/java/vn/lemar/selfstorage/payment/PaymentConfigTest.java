package vn.lemar.selfstorage.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vn.lemar.selfstorage.payment.application.VnPayGateway;
import vn.lemar.selfstorage.payment.config.PaymentConfig;
import vn.lemar.selfstorage.payment.config.VnPayProperties;

/**
 * Bắt lỗi cấu hình sớm: nếu {@code VnPayProperties} không được đăng ký thành bean thì
 * {@code VnPayGateway} không dựng được và ứng dụng chết ngay lúc khởi động.
 */
class PaymentConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PaymentConfig.class)
            .withBean(VnPayGateway.class);

    @Test
    void loadsVnpayConfigFromProperties() {
        runner.withPropertyValues(
                        "payment.provider=vnpay",
                        "vnpay.tmn-code=TESTTMN1",
                        "vnpay.hash-secret=TESTSECRET0123456789TESTSECRET01",
                        "vnpay.pay-url=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
                        "vnpay.return-url=http://localhost:8080/api/payments/vnpay/return")
                .run(context -> {
                    assertThat(context).hasSingleBean(VnPayGateway.class);
                    assertThat(context.getBean(VnPayProperties.class).tmnCode()).isEqualTo("TESTTMN1");
                });
    }

    /** payOS là cổng mặc định: không khai {@code payment.provider} thì VNPay không được dựng. */
    @Test
    void vnpayIsNotActiveByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(VnPayGateway.class));
    }
}
