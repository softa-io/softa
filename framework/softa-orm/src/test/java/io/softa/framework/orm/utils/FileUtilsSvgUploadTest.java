package io.softa.framework.orm.utils;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import io.softa.framework.base.exception.BaseException;
import io.softa.framework.orm.enums.FileType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An SVG is accepted as SVG only: it can carry script, so it is not one of the text types a file may be
 * uploaded as under another one's name or type.
 */
class FileUtilsSvgUploadTest {

    private static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] XML = "<?xml version=\"1.0\"?><rows><row id=\"1\"/></rows>"
            .getBytes(StandardCharsets.UTF_8);

    private static FileType typeOf(String name, String contentType, byte[] content) {
        return FileUtils.getActualFileType(new MockMultipartFile("file", name, contentType, content));
    }

    @Test
    void anSvgUploadedAsTextIsRefused() {
        // Refused by the framework's own check, whichever of its exceptions carries the reason.
        assertThatThrownBy(() -> typeOf("notes.txt", "text/plain", SVG)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> typeOf("data.xml", "text/xml", SVG)).isInstanceOf(BaseException.class);
    }

    @Test
    void anSvgUploadedAsSvgIsAccepted() {
        assertThat(typeOf("logo.svg", "image/svg+xml", SVG)).isEqualTo(FileType.SVG);
    }

    @Test
    void theOtherTextTypesStillStandInForEachOther() {
        assertThat(typeOf("rows.txt", "text/plain", XML)).isEqualTo(FileType.XML);
    }
}
