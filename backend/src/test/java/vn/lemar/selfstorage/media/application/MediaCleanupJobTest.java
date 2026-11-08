package vn.lemar.selfstorage.media.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.media.application.exception.MediaStorageException;

class MediaCleanupJobTest {

    private final MediaService mediaService = mock(MediaService.class);

    @Test
    void rejectsNonPositiveMaxAge() {
        assertThatThrownBy(() -> new MediaCleanupJob(mediaService, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneFailingRowDoesNotStopTheRest() {
        when(mediaService.abandonedIds(any())).thenReturn(List.of(1L, 2L, 3L));
        when(mediaService.cleanupAbandoned(eq(1L), any())).thenThrow(new MediaStorageException("down"));
        when(mediaService.cleanupAbandoned(eq(2L), any())).thenReturn(true);
        when(mediaService.cleanupAbandoned(eq(3L), any())).thenReturn(false);

        new MediaCleanupJob(mediaService, Duration.ofDays(3)).run();

        verify(mediaService).cleanupAbandoned(eq(2L), any());
        verify(mediaService).cleanupAbandoned(eq(3L), any());
    }
}
