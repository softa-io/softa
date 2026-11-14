package io.softa.framework.web.controller;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import io.softa.framework.orm.file.FileOpenKeys.KeyCheck;
import io.softa.framework.orm.file.FileOpenKeys.KeyStatus;
import io.softa.framework.orm.service.FileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileKeyResponsesTest {

    private final FileService files = mock(FileService.class);

    @Test
    void aValidKeyRedirectsToAStorageLinkSignedNow_neverCached() {
        String link = "https://storage.example/bucket/a.pdf?X-Amz-Signature=abc&response-content-disposition=attachment";
        when(files.getStorageLink(5L, true)).thenReturn(Optional.of(link));

        ResponseEntity<String> response = FileKeyResponses.open(new KeyCheck(KeyStatus.VALID, 5L), files, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(link);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void inlineIsTheDefault() {
        when(files.getStorageLink(5L, false)).thenReturn(Optional.of("https://storage.example/a.png?sig=1"));

        ResponseEntity<String> response = FileKeyResponses.open(new KeyCheck(KeyStatus.VALID, 5L), files, false);

        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("https://storage.example/a.png?sig=1");
    }

    @Test
    void eachRefusalHasItsOwnStatus_inPlainText_neverCached() {
        assertRefused(FileKeyResponses.open(new KeyCheck(KeyStatus.MALFORMED, null), files, false),
                HttpStatus.NOT_FOUND, FileKeyResponses.NOT_FOUND);
        assertRefused(FileKeyResponses.open(new KeyCheck(KeyStatus.WRONG_SIGNATURE, null), files, false),
                HttpStatus.FORBIDDEN, FileKeyResponses.WRONG_ACCOUNT);
        assertRefused(FileKeyResponses.open(new KeyCheck(KeyStatus.EXPIRED, null), files, false),
                HttpStatus.GONE, FileKeyResponses.EXPIRED);
        verify(files, never()).getStorageLink(anyLong(), anyBoolean());
    }

    @Test
    void aGenuineKeyForAFileThatIsGone_isNotFound() {
        when(files.getStorageLink(5L, false)).thenReturn(Optional.empty());

        assertRefused(FileKeyResponses.open(new KeyCheck(KeyStatus.VALID, 5L), files, false),
                HttpStatus.NOT_FOUND, FileKeyResponses.NOT_FOUND);
    }

    @Test
    void downloadIsAskedForWithOneOrTrue() {
        assertThat(FileKeyResponses.isDownload("1")).isTrue();
        assertThat(FileKeyResponses.isDownload("true")).isTrue();
        assertThat(FileKeyResponses.isDownload("TRUE")).isTrue();
        assertThat(FileKeyResponses.isDownload(null)).isFalse();
        assertThat(FileKeyResponses.isDownload("0")).isFalse();
        assertThat(FileKeyResponses.isDownload("yes")).isFalse();
    }

    private static void assertRefused(ResponseEntity<String> response, HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo(message);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_PLAIN)).isTrue();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isNull();
    }
}
