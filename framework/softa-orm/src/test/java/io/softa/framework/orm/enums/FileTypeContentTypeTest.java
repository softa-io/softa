package io.softa.framework.orm.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypeContentTypeTest {

    @Test
    void namesTheMimeTypeOfTheExtension() {
        assertThat(FileType.contentTypeOf("EmpDocumentFile/2026/contract.pdf")).isEqualTo("application/pdf");
        assertThat(FileType.contentTypeOf("scan.PNG")).isEqualTo("image/png");
        assertThat(FileType.contentTypeOf("scan.jpeg")).isEqualTo("image/jpeg");
    }

    @Test
    void fallsBackToOctetStreamWhenTheExtensionSaysNothing() {
        assertThat(FileType.contentTypeOf("README")).isEqualTo("application/octet-stream");
        assertThat(FileType.contentTypeOf("archive.unknownext")).isEqualTo("application/octet-stream");
        assertThat(FileType.contentTypeOf("trailing.")).isEqualTo("application/octet-stream");
        assertThat(FileType.contentTypeOf(null)).isEqualTo("application/octet-stream");
    }

    @Test
    void readsTheTypeOfTheExtension() {
        assertThat(FileType.ofFileName("1/EmpDocument/logo.SVG")).contains(FileType.SVG);
        assertThat(FileType.ofFileName("scan.jpeg")).contains(FileType.JPG);
        assertThat(FileType.ofFileName("README")).isEmpty();
        assertThat(FileType.ofFileName("archive.unknownext")).isEmpty();
        assertThat(FileType.ofFileName(null)).isEmpty();
    }
}
