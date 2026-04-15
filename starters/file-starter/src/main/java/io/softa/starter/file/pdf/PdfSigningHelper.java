package io.softa.starter.file.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;

import io.softa.framework.base.exception.SystemException;

/**
 * Helper for locating and stamping handwritten signatures onto a PDF.
 */
public final class PdfSigningHelper {

    private PdfSigningHelper() {
    }

    public record ResolvedPlacement(int page, float x, float y, float width, float height) {
    }

    public static ResolvedPlacement resolveFieldPlacement(byte[] pdfBytes, String fieldCode) {
        if (StringUtils.isBlank(fieldCode)) {
            return null;
        }
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                return null;
            }
            PDField field = acroForm.getField(fieldCode);
            if (!(field instanceof PDTerminalField terminalField)) {
                return null;
            }
            List<PDAnnotationWidget> widgets = terminalField.getWidgets();
            if (widgets.isEmpty()) {
                return null;
            }
            PDAnnotationWidget widget = widgets.getFirst();
            PDRectangle rect = widget.getRectangle();
            if (rect == null) {
                return null;
            }
            int pageNumber = resolveWidgetPageNumber(document, widget);
            return new ResolvedPlacement(
                    pageNumber,
                    rect.getLowerLeftX(),
                    rect.getLowerLeftY(),
                    rect.getWidth(),
                    rect.getHeight()
            );
        } catch (IOException e) {
            throw new SystemException("Failed to resolve the sign field position in PDF.", e);
        }
    }

    public static int getPageCount(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new SystemException("Failed to read the PDF page count.", e);
        }
    }

    public static byte[] stampSignature(byte[] pdfBytes,
                                        byte[] signatureImageBytes,
                                        ResolvedPlacement placement,
                                        boolean flattenToPdf,
                                        String imageScaleMode) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDPage page = document.getPage(placement.page() - 1);
            PDImageXObject image = PDImageXObject.createFromByteArray(document, signatureImageBytes, "signature");

            float targetWidth;
            float targetHeight;
            if ("STRETCH".equalsIgnoreCase(imageScaleMode)) {
                targetWidth = placement.width();
                targetHeight = placement.height();
            } else {
                float scale = Math.min(
                        placement.width() / image.getWidth(),
                        placement.height() / image.getHeight()
                );
                targetWidth = image.getWidth() * scale;
                targetHeight = image.getHeight() * scale;
            }

            float x = placement.x();
            float y = placement.y();
            if (!"STRETCH".equalsIgnoreCase(imageScaleMode)) {
                x += (placement.width() - targetWidth) / 2F;
                y += (placement.height() - targetHeight) / 2F;
            }

            try (PDPageContentStream contentStream = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                contentStream.drawImage(image, x, y, targetWidth, targetHeight);
            }

            if (flattenToPdf) {
                PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
                if (acroForm != null) {
                    acroForm.flatten();
                }
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            document.save(outputStream);
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new SystemException("Failed to stamp the signature image onto the PDF.", e);
        }
    }

    /**
     * Finds the 1-based page number for the given widget annotation by scanning each page's annotations.
     */
    private static int resolveWidgetPageNumber(PDDocument document, PDAnnotationWidget widget) throws IOException {
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            for (PDAnnotation annotation : document.getPage(i).getAnnotations()) {
                if (annotation.getCOSObject() == widget.getCOSObject()) {
                    return i + 1;
                }
            }
        }
        return 1;
    }
}
