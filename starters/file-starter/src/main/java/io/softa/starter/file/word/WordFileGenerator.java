package io.softa.starter.file.word;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.config.ConfigureBuilder;
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy;
import com.deepoove.poi.template.MetaTemplate;
import com.deepoove.poi.template.run.RunTemplate;
import org.docx4j.Docx4J;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;

import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.placeholder.PlaceholderUtils;

/**
 * Word document file generator.
 * Renders Word templates (.docx) with dynamic data via poi-tl,
 * and converts DOCX to PDF via docx4j.
 */
public final class WordFileGenerator {

    private WordFileGenerator() {}

    private static final Configure CONFIGURE;

    static {
        ConfigureBuilder builder = Configure.builder();
        // Enable Spring EL expressions in templates
        builder.useSpringEL();
        // Register LoopRowTableRenderPolicy for OneToMany dynamic table row looping.
        // Use {{#fieldName}} in Word templates to render a list as looping table rows.
        builder.addPlugin('#', new LoopRowTableRenderPolicy());
        CONFIGURE = builder.build();
    }

    /**
     * Extract plain text variable names from a Word template.
     * Compiles the template via poi-tl and reads all element templates,
     * keeping only RunTemplate instances whose sign is not a plugin tag,
     * and whose variable name is validated by {@link PlaceholderUtils#isVariable}.
     *
     * @param templateInputStream the input stream of the Word template file
     * @return deduplicated list of plain text variable names
     */
    public static List<String> extractVariables(InputStream templateInputStream) {
        try (XWPFTemplate template = XWPFTemplate.compile(templateInputStream, CONFIGURE)) {
            List<String> variables = new ArrayList<>();
            for (MetaTemplate meta : template.getElementTemplates()) {
                if (!(meta instanceof RunTemplate rt)) {
                    continue;
                }
                // Skip image (#) and table (>) plugin tags
                char sign = rt.getSign();
                if (sign == '#' || sign == '>') {
                    continue;
                }
                String tagName = rt.variable();
                // Use PlaceholderUtils.isVariable to validate the tag name is a field variable
                String placeholder = StringConstant.PLACEHOLDER_PREFIX + tagName + StringConstant.PLACEHOLDER_SUFFIX;
                if (tagName != null && PlaceholderUtils.isVariable(placeholder)
                        && !variables.contains(tagName)) {
                    variables.add(tagName);
                }
            }
            return variables;
        } catch (IOException e) {
            throw new SystemException("Failed to extract variables from Word template.", e);
        }
    }

    /**
     * Render the document from the Word template with data.
     *
     * @param templateInputStream the input stream of the Word template
     * @param data the data object to render the document
     * @param outputStream the output stream of the rendered document
     */
    public static void renderDocument(InputStream templateInputStream, Object data, OutputStream outputStream) {
        try (XWPFTemplate xwpfTemplate = XWPFTemplate.compile(templateInputStream, CONFIGURE)) {
            xwpfTemplate.render(data);
            xwpfTemplate.write(outputStream);
        } catch (IOException e) {
            throw new SystemException("Failed to render the document from Word template.", e);
        }
    }

    /**
     * Convert the DOCX content bytes to PDF bytes via docx4j.
     *
     * @param docxContent the content of the DOCX file
     * @return the content of the PDF file
     */
    public static byte[] convertDocxToPdf(byte[] docxContent) {
        try (ByteArrayInputStream docInputStream = new ByteArrayInputStream(docxContent);
             ByteArrayOutputStream pdfOutputStream = new ByteArrayOutputStream()) {
            convertDocxToPdf(docInputStream, pdfOutputStream);
            return pdfOutputStream.toByteArray();
        } catch (Docx4JException | IOException e) {
            throw new SystemException("Failed to convert the DOCX document to PDF", e);
        }
    }

    /**
     * Convert the DOCX file to PDF via docx4j.
     *
     * @param docxInputStream the input stream of the DOCX file
     * @param pdfOutputStream the output stream of the PDF file
     * @throws Docx4JException if the conversion fails
     */
    public static void convertDocxToPdf(InputStream docxInputStream, OutputStream pdfOutputStream) throws Docx4JException {
        WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.load(docxInputStream);
        Docx4J.toPDF(wordMLPackage, pdfOutputStream);
    }
}
