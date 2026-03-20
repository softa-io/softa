package io.softa.starter.file.file;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import org.openpdf.text.Document;
import org.openpdf.text.PageSize;
import org.openpdf.text.html.simpleparser.HTMLWorker;
import org.openpdf.text.pdf.PdfWriter;

import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.placeholder.HtmlTemplateRenderer;

/**
 * PDF file generator for rich text (HTML) templates.
 * Uses {@link HtmlTemplateRenderer} for HTML template rendering and OpenPDF for HTML-to-PDF conversion.
 */
public final class PdfFileGenerator {

    private PdfFileGenerator() {}

    /**
     * Convert rendered HTML string to PDF bytes using OpenPDF.
     *
     * @param html the rendered HTML string
     * @return the PDF content as byte array
     */
    public static byte[] convertHtmlToPdf(String html) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4);
            PdfWriter.getInstance(document, outputStream);
            document.open();
            HTMLWorker htmlWorker = new HTMLWorker(document);
            htmlWorker.parse(new StringReader(html));
            document.close();
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new SystemException("Failed to convert HTML to PDF.", e);
        }
    }
}
