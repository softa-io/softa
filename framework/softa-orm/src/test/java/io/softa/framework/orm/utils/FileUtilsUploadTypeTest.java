package io.softa.framework.orm.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.enums.FileType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An upload is taken by its content, checked against the two things it claims to be: the content type the browser
 * sent and its file name. Browsers send different content types for the same Word document — a zip, a legacy Word
 * file, nothing — and each of those used to be refused as "Failed to read the uploaded file!", a message that said
 * nothing about why.
 */
class FileUtilsUploadTypeTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static byte[] wordDocument() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes());
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    private static FileType typeOf(String name, String contentType, byte[] content) {
        return FileUtils.getActualFileType(new MockMultipartFile("file", name, contentType, content));
    }

    @Test
    void aWordDocumentIsAcceptedWhateverTheBrowserCallsIt() throws IOException {
        byte[] doc = wordDocument();
        assertThat(typeOf("plan (2).docx", DOCX, doc)).isEqualTo(FileType.DOCX);
        assertThat(typeOf("plan (2).docx", "application/zip", doc)).isEqualTo(FileType.DOCX);
        assertThat(typeOf("plan (2).docx", "application/x-zip-compressed", doc)).isEqualTo(FileType.DOCX);
        assertThat(typeOf("plan (2).docx", "application/msword", doc)).isEqualTo(FileType.DOCX);
        assertThat(typeOf("plan (2).docx", "application/octet-stream", doc)).isEqualTo(FileType.DOCX);
        assertThat(typeOf("PLAN.DOCX", null, doc)).isEqualTo(FileType.DOCX);
    }

    @Test
    void aFileWhoseContentMatchesNeitherClaimIsRefusedSayingSo() {
        assertThatThrownBy(() -> typeOf("photo.docx", DOCX, PNG))
                .hasMessageContaining("does not match")
                .hasMessageNotContaining("Failed to read");
    }

    @Test
    void aFileOfNoSupportedTypeIsRefusedAsUnsupported() {
        assertThatThrownBy(() -> typeOf("setup.exe", "application/octet-stream", PNG))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("is not supported");
    }

    @Test
    void anImageRenamedToAnotherImageTypeIsStillAccepted() {
        assertThat(typeOf("photo.jpg", "image/jpeg", PNG)).isEqualTo(FileType.PNG);
    }
}
