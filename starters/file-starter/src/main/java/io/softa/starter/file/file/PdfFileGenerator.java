package io.softa.starter.file.file;

import java.io.ByteArrayOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.openpdf.pdf.ITextRenderer;

import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.utils.Assert;

/**
 * PDF file generator for rich text (HTML) templates.
 * Converts rendered HTML strings to PDF bytes using OpenPDF (ITextRenderer).
 */
@Slf4j
public final class PdfFileGenerator {

    private PdfFileGenerator() {}

    /**
     * Convert rendered HTML string to PDF bytes using OpenPDF.
     *
     * @param html the rendered HTML string, must not be blank
     * @return the PDF content as byte array
     */
    public static byte[] convertHtmlToPdf(String html) {
        Assert.notBlank(html, "The HTML content for PDF generation must not be blank.");
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(outputStream);
            return outputStream.toByteArray();
        } catch (Exception e) {
            log.error("Failed to convert HTML to PDF, HTML length: {}", html.length(), e);
            throw new SystemException("Failed to convert HTML to PDF.", e);
        }
    }
}
