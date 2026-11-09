package vn.lemar.selfstorage.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SmtpEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final SmtpEmailSender sender = new SmtpEmailSender(mailSender, "noreply@lemar.vn");

    @AfterEach
    void clearSynchronizations() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sendsMessageWithGivenFields() {
        boolean result = sender.send("a@b.vn", "Tiêu đề", "Nội dung");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(result).isTrue();
        assertThat(sent.getFrom()).isEqualTo("noreply@lemar.vn");
        assertThat(sent.getTo()).containsExactly("a@b.vn");
        assertThat(sent.getSubject()).isEqualTo("Tiêu đề");
        assertThat(sent.getText()).isEqualTo("Nội dung");
    }

    @Test
    void sendsHtmlMessage() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        boolean result = sender.sendHtml("a@b.vn", "Tiêu đề", "<p>Nội dung</p>");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        sent.saveChanges();
        assertThat(result).isTrue();
        assertThat(sent.getSubject()).isEqualTo("Tiêu đề");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("a@b.vn");
        assertThat(sent.getContentType()).contains("text/html");
        assertThat(sent.getContent()).isEqualTo("<p>Nội dung</p>");
    }

    @Test
    void blankFromFallsBackToSmtpUsername() {
        SmtpEmailSender gmail = new SmtpEmailSender(mailSender, "", "shop@gmail.com");

        gmail.send("a@b.vn", "s", "b");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo("shop@gmail.com");
    }

    @Test
    void allBlankFallsBackToDefaultFrom() {
        SmtpEmailSender unconfigured = new SmtpEmailSender(mailSender, " ", null);

        unconfigured.send("a@b.vn", "s", "b");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo(SmtpEmailSender.DEFAULT_FROM);
    }

    @Test
    void explicitFromWinsOverSmtpUsername() {
        SmtpEmailSender gmail = new SmtpEmailSender(mailSender, "Lemar <shop@gmail.com>", "shop@gmail.com");

        gmail.send("a@b.vn", "s", "b");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo("Lemar <shop@gmail.com>");
    }

    @Test
    void swallowsMailFailureAndReturnsFalse() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThat(sender.send("a@b.vn", "s", "b")).isFalse();
    }

    @Test
    void swallowsHtmlFailureAndReturnsFalse() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        assertThat(sender.sendHtml("a@b.vn", "s", "<p>b</p>")).isFalse();
    }

    @Test
    void skipsBlankRecipient() {
        assertThat(sender.send(" ", "s", "b")).isFalse();
        assertThat(sender.send(null, "s", "b")).isFalse();
        verifyNoInteractions(mailSender);
    }

    @Test
    void defersToAfterCommitWhenTransactionActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            boolean result = sender.send("a@b.vn", "s", "b");

            assertThat(result).isTrue();
            verifyNoInteractions(mailSender);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(mailSender).send(any(SimpleMailMessage.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void doesNotSendWhenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            sender.send("a@b.vn", "s", "b");

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization ->
                            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verifyNoInteractions(mailSender);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void asyncSenderReturnsBeforeSmtpFinishesAndStillDelivers() throws Exception {
        java.util.concurrent.CountDownLatch smtpBusy = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            smtpBusy.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        }).when(mailSender).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
        SmtpEmailSender async = new SmtpEmailSender(mailSender, "noreply@lemar.vn", "", true);
        try {
            // SMTP đang "treo" mà send vẫn trả ngay: caller (request duyệt…) không chờ mail.
            assertThat(async.send("to@lemar.vn", "Tiêu đề", "Nội dung")).isTrue();
            smtpBusy.countDown();
            org.mockito.Mockito.verify(mailSender, org.mockito.Mockito.timeout(5000))
                    .send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
        } finally {
            async.destroy();
        }
    }
}
