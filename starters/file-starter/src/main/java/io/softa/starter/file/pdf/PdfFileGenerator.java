package io.softa.starter.file.pdf;

import java.io.ByteArrayOutputStream;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.util.XRLog;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;

import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.utils.Assert;

/**
 * Converts rendered HTML strings to PDF bytes using OpenHTMLtoPDF (PDFBox backend).
 * Font registration is delegated to {@link FontProvider}.
 */
@Slf4j
public final class PdfFileGenerator {

    static {
        XRLog.setLoggingEnabled(false);
    }

    private PdfFileGenerator() {}

    /**
     * Convert rendered HTML string to PDF bytes.
     *
     * @param html the rendered HTML string, must not be blank
     * @return the PDF content as byte array
     */
    public static byte[] convertHtmlToPdf(String html) {
        Assert.notBlank(html, "The HTML content for PDF generation must not be blank.");
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document jsoupDoc = Jsoup.parse(html);
            jsoupDoc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);

            PdfRendererBuilder builder = new PdfRendererBuilder();
            FontProvider.registerFonts(builder);
            builder.withW3cDocument(new W3CDom().fromJsoup(jsoupDoc), null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to convert HTML to PDF, HTML length: {}", html.length(), e);
            throw new SystemException("Failed to convert HTML to PDF.", e);
        }
    }
}
