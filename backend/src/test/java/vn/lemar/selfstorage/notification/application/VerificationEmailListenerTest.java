package vn.lemar.selfstorage.notification.application;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.lemar.selfstorage.identity.application.VerificationEmailRequested;
import vn.lemar.selfstorage.notification.EmailSender;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VerificationEmailListenerTest {

    @Mock
    private EmailSender emailSender;

    @InjectMocks
    private VerificationEmailListener listener;

    @Test
    void sendsHtmlEmailWithCodeWrappedInStandardLayout() {
        listener.onVerificationEmailRequested(
                new VerificationEmailRequested("customer@example.com", "123456", Duration.ofMinutes(10)));

        verify(emailSender).sendHtml(eq("customer@example.com"),
                eq("Mã xác minh email đăng ký Kho Mộc"),
                argThat(html -> html.contains("Mã xác minh email của bạn là: 123456")
                        && html.contains("Mã có hiệu lực trong 10 phút")
                        && html.contains("KHO MỘC")));
    }

    @Test
    void rendersConfiguredTtlInMinutes() {
        listener.onVerificationEmailRequested(
                new VerificationEmailRequested("customer@example.com", "654321", Duration.ofMinutes(15)));

        verify(emailSender).sendHtml(eq("customer@example.com"),
                eq("Mã xác minh email đăng ký Kho Mộc"),
                argThat(html -> html.contains("Mã xác minh email của bạn là: 654321")
                        && html.contains("Mã có hiệu lực trong 15 phút")));
    }
}
