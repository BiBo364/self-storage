package vn.lemar.selfstorage.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.media.domain.MediaUploadStatus;
import vn.lemar.selfstorage.media.repository.MediaUploadRepository;

/** Đối chiếu URL client gửi với upload còn dùng được của account. */
class MediaUrlLookupServiceTest {

    private final MediaUploadRepository mediaUploads = mock(MediaUploadRepository.class);
    private final MediaUrlLookupService lookup = new MediaUrlLookupService(mediaUploads);

    @Test
    void returnsUrlsNotUploadedByAccount() {
        when(mediaUploads.findUrlsOwnedBy(eq(7L), anyCollection(), anyCollection())).thenReturn(List.of("a"));

        assertThat(lookup.notUploadedBy(7L, Arrays.asList("a", "b", "a", null))).containsExactly("b");
        verify(mediaUploads).findUrlsOwnedBy(7L, List.of("a", "b"),
                List.of(MediaUploadStatus.PENDING, MediaUploadStatus.ATTACHED));
    }

    @Test
    void emptyInputSkipsQuery() {
        assertThat(lookup.notUploadedBy(7L, null)).isEmpty();
        assertThat(lookup.notUploadedBy(7L, List.of())).isEmpty();
        assertThat(lookup.notUploadedBy(7L, Arrays.asList((String) null))).isEmpty();
        verifyNoInteractions(mediaUploads);
    }

    @Test
    void allOwnedMeansNothingMissing() {
        when(mediaUploads.findUrlsOwnedBy(anyLong(), anyCollection(), anyCollection())).thenReturn(List.of("a"));
        assertThat(lookup.notUploadedBy(1L, List.of("a"))).isEmpty();
    }
}
