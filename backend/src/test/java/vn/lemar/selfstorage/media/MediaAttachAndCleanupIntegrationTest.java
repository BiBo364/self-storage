package vn.lemar.selfstorage.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.media.application.MediaService;
import vn.lemar.selfstorage.media.application.exception.MediaStorageException;
import vn.lemar.selfstorage.support.application.SupportService;
import vn.lemar.selfstorage.support.application.SupportService.CreateInput;

/**
 * Attach phía server (flow lưu URL → upload {@code Attached}) và cron dọn upload bỏ ngang, chạy thật trên Postgres;
 * storage mock.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MediaAttachAndCleanupIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private FileStorage fileStorage;
    @MockBean
    private JavaMailSender mailSender;

    @Autowired
    private MediaAttachments attachments;
    @Autowired
    private MediaService mediaService;
    @Autowired
    private SupportService support;
    @Autowired
    private JdbcTemplate jdbc;

    private long customerId;
    private long otherId;

    @BeforeEach
    void setUp() {
        customerId = id("select id from accounts where email = 'customer1@lemar.vn'");
        otherId = id("select id from accounts where email = 'fs1@lemar.vn'");
        when(mailSender.createMimeMessage()).thenReturn(
                new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties())));
    }

    @Test
    void attachMarksOnlyOwnPendingUploads() {
        String url = url();
        long mine = upload(customerId, url, "Pending", "now()");
        long others = upload(otherId, url(), "Pending", "now()");
        long canceled = upload(customerId, url(), "Canceled", "now()");

        int attached = attachments.attach(customerId, List.of(url, urlOf(others), urlOf(canceled), " ", url));

        assertThat(attached).isEqualTo(1);
        assertThat(status(mine)).isEqualTo("Attached");
        assertThat(status(others)).isEqualTo("Pending");
        assertThat(status(canceled)).isEqualTo("Canceled");
        assertThat(attachments.attach(customerId, null)).isZero();
        assertThat(attachments.attach(customerId, List.of(url))).isZero();
    }

    @Test
    void supportRequestAttachesItsPhotosServerSide() {
        String url = url();
        long row = upload(customerId, url, "Pending", "now()");
        long facilityId = id("select id from facilities where code = 'Q7'");

        support.create(customerId, new CreateInput(null, facilityId, "OTHER", "Cửa kẹt", "Cửa khoang khó mở",
                List.of(url)));

        assertThat(status(row)).isEqualTo("Attached");
    }

    @Test
    void cleanupDeletesOnlyAbandonedUploadsAndKeepsRowWhenStorageFails() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(3));
        long abandoned = upload(customerId, url(), "Pending", "now() - interval '4 days'");
        long stuck = upload(customerId, null, "Uploading", "now() - interval '4 days'");
        long fresh = upload(customerId, url(), "Pending", "now() - interval '1 day'");
        long attachedOld = upload(customerId, url(), "Attached", "now() - interval '30 days'");

        List<Long> due = mediaService.abandonedIds(cutoff);
        assertThat(due).contains(abandoned, stuck).doesNotContain(fresh, attachedOld);

        assertThat(mediaService.cleanupAbandoned(abandoned, cutoff)).isTrue();
        verify(fileStorage).delete("pub-" + abandoned);
        assertThat(status(abandoned)).isEqualTo("Deleted");
        assertThat(mediaService.cleanupAbandoned(abandoned, cutoff)).isFalse();

        assertThat(mediaService.cleanupAbandoned(stuck, cutoff)).isTrue();
        assertThat(status(stuck)).isEqualTo("Deleted");
        assertThat(mediaService.cleanupAbandoned(fresh, cutoff)).isFalse();
        assertThat(status(fresh)).isEqualTo("Pending");

        long failing = upload(customerId, url(), "Pending", "now() - interval '4 days'");
        doThrow(new MediaStorageException("down")).when(fileStorage).delete("pub-" + failing);
        assertThatThrownBy(() -> mediaService.cleanupAbandoned(failing, cutoff))
                .isInstanceOf(MediaStorageException.class);
        assertThat(status(failing)).isEqualTo("Pending");
        verify(fileStorage, never()).delete("pub-" + stuck);
        verify(fileStorage, never()).delete("pub-" + fresh);
    }

    // ------------------------------------------------------------------ helpers

    private long upload(long accountId, String url, String status, String updatedAt) {
        long rowId = id("insert into media_uploads (account_id, batch_id, url, status, updated_at) values ("
                + accountId + ", '" + UUID.randomUUID() + "', " + (url == null ? "null" : "'" + url + "'") + ", '"
                + status + "', " + updatedAt + ") returning id");
        if (url != null) {
            jdbc.update("update media_uploads set public_id = 'pub-' || id where id = ?", rowId);
        }
        return rowId;
    }

    private static String url() {
        return "https://res.cloudinary.com/test/ma-" + SEQ.incrementAndGet() + "-" + System.nanoTime() + ".png";
    }

    private String urlOf(long rowId) {
        return jdbc.queryForObject("select url from media_uploads where id = ?", String.class, rowId);
    }

    private String status(long rowId) {
        return jdbc.queryForObject("select status from media_uploads where id = ?", String.class, rowId);
    }

    private long id(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
