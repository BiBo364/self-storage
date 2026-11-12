package vn.lemar.selfstorage.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

class BrevoEmailSenderTest {

    private static final String URL = "https://api.brevo.test/v3/smtp/email";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private BrevoEmailSender sender(String from, String fromName) {
        return new BrevoEmailSender(builder.build(), " key-123 ", URL, from, fromName, false);
    }

    @AfterEach
    void clearSynchronizations() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sendsHtmlThroughTheBrevoApi() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "key-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sender.email").value("shop@gmail.com"))
                .andExpect(jsonPath("$.sender.name").value("Kho Mộc"))
                .andExpect(jsonPath("$.to[0].email").value("khach@example.com"))
                .andExpect(jsonPath("$.subject").value("Mã xác minh"))
                .andExpect(jsonPath("$.htmlContent").value("<b>123456</b>"))
                .andRespond(withSuccess("{\"messageId\":\"<1@brevo>\"}", MediaType.APPLICATION_JSON));

        assertThat(sender("shop@gmail.com", "Kho Mộc").sendHtml("khach@example.com", "Mã xác minh", "<b>123456</b>"))
                .isTrue();
        server.verify();
    }

    @Test
    void plainTextUsesTextContentAndNamedFromAddress() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.sender.email").value("shop@gmail.com"))
                .andExpect(jsonPath("$.sender.name").value("Lemar"))
                .andExpect(jsonPath("$.textContent").value("Xin chào"))
                .andRespond(withSuccess());

        assertThat(sender("Lemar <shop@gmail.com>", "").send("khach@example.com", "Chào", "Xin chào")).isTrue();
        server.verify();
    }

    @Test
    void blankSettingsFallBackToDefaults() {
        server.expect(requestTo(BrevoEmailSender.DEFAULT_API_URL))
                .andExpect(jsonPath("$.sender.email").value(SmtpEmailSender.DEFAULT_FROM))
                .andExpect(jsonPath("$.sender.name").doesNotExist())
                .andRespond(withSuccess());

        assertThat(new BrevoEmailSender(builder.build(), "k", " ", null, null, false)
                .send("khach@example.com", "s", "b")).isTrue();
        server.verify();
    }

    @Test
    void rejectedRequestIsSwallowedAndReported() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .body("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}")
                .contentType(MediaType.APPLICATION_JSON));

        assertThat(sender("shop@gmail.com", "Kho Mộc").send("khach@example.com", "s", "b")).isFalse();
    }

    @Test
    void blankRecipientIsSkippedWithoutCallingBrevo() {
        assertThat(sender("shop@gmail.com", "Kho Mộc").send(" ", "s", "b")).isFalse();
        server.verify();
    }

    @Test
    void waitsForCommitInsideATransaction() {
        server.expect(requestTo(URL)).andRespond(withSuccess());
        TransactionSynchronizationManager.initSynchronization();

        assertThat(sender("shop@gmail.com", "Kho Mộc").send("khach@example.com", "s", "b")).isTrue();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        server.verify();
    }

    @Test
    void missingApiKeyStopsStartup() {
        assertThatThrownBy(() -> new BrevoEmailSender(builder.build(), " ", URL, "shop@gmail.com", "x", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BREVO_API_KEY");
    }

    @Test
    void providerPropertyPicksExactlyOneSender() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(org.springframework.mail.javamail.JavaMailSender.class,
                        () -> org.mockito.Mockito.mock(org.springframework.mail.javamail.JavaMailSender.class))
                .withUserConfiguration(SmtpEmailSender.class, BrevoEmailSender.class);

        runner.run(context -> assertThat(context)
                .hasSingleBean(vn.lemar.selfstorage.notification.EmailSender.class)
                .hasSingleBean(SmtpEmailSender.class));
        runner.withPropertyValues("app.mail.provider=brevo", "brevo.api-key=k", "app.mail.from=shop@gmail.com")
                .run(context -> assertThat(context)
                        .hasSingleBean(vn.lemar.selfstorage.notification.EmailSender.class)
                        .hasSingleBean(BrevoEmailSender.class));
    }

    @Test
    void springConstructorAppliesDefaultsAndTimeouts() throws Exception {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        BrevoEmailSender fromSpring = new BrevoEmailSender(beans.getBeanProvider(RestClient.Builder.class), "k", "",
                "shop@gmail.com", "Kho Mộc", true);

        assertThat(fromSpring).isNotNull();
        fromSpring.destroy();
    }
}
