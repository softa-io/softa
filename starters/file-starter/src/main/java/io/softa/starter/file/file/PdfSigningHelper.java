package io.softa.starter.file.file;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.exception.SystemException;
import org.openpdf.text.Image;
import org.openpdf.text.pdf.AcroFields;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfStamper;

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
        try (PdfReader reader = new PdfReader(pdfBytes)) {
            AcroFields acroFields = reader.getAcroFields();
            float[] positions = acroFields.getFieldPositions(fieldCode);
            if (positions == null || positions.length < 5) {
                return null;
            }
            return new ResolvedPlacement(
                    (int) positions[0],
                    positions[1],
                    positions[2],
                    positions[3] - positions[1],
                    positions[4] - positions[2]
            );
        } catch (IOException e) {
            throw new SystemException("Failed to resolve the sign field position in PDF.", e);
        }
    }

    public static int getPageCount(byte[] pdfBytes) {
        try (PdfReader reader = new PdfReader(pdfBytes)) {
            return reader.getNumberOfPages();
        } catch (IOException e) {
            throw new SystemException("Failed to read the PDF page count.", e);
        }
    }

    public static byte[] stampSignature(byte[] pdfBytes,
                                        byte[] signatureImageBytes,
                                        ResolvedPlacement placement,
                                        boolean flattenToPdf,
                                        String imageScaleMode) {
        try (PdfReader reader = new PdfReader(pdfBytes);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             PdfStamper stamper = new PdfStamper(reader, outputStream)) {
            PdfContentByte overContent = stamper.getOverContent(placement.page());
            Image image = Image.getInstance(signatureImageBytes);
            scaleImage(image, placement.width(), placement.height(), imageScaleMode);
            float x = placement.x();
            float y = placement.y();
            if (!"STRETCH".equalsIgnoreCase(imageScaleMode)) {
                x += (placement.width() - image.getScaledWidth()) / 2F;
                y += (placement.height() - image.getScaledHeight()) / 2F;
            }
            image.setAbsolutePosition(x, y);
            overContent.addImage(image);
            stamper.setFormFlattening(flattenToPdf);
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new SystemException("Failed to stamp the signature image onto the PDF.", e);
        }
    }

    private static void scaleImage(Image image, float maxWidth, float maxHeight, String imageScaleMode) {
        if ("STRETCH".equalsIgnoreCase(imageScaleMode)) {
            image.scaleAbsolute(maxWidth, maxHeight);
            return;
        }
        image.scaleToFit(maxWidth, maxHeight);
    }
}
