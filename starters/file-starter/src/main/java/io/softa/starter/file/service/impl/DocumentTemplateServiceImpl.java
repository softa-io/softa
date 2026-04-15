package io.softa.starter.file.service.impl;

import java.io.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.framework.base.placeholder.TemplateEngine;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.domain.SubQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.dto.UploadFileDTO;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.file.entity.DocumentTemplate;
import io.softa.starter.file.enums.DocumentTemplateType;
import io.softa.starter.file.pdf.PdfFileGenerator;
import io.softa.starter.file.service.DocumentTemplateService;
import io.softa.starter.file.word.WordFileGenerator;

/**
 * DocumentTemplate Model Service Implementation
 */
@Service
public class DocumentTemplateServiceImpl extends EntityServiceImpl<DocumentTemplate, Long> implements DocumentTemplateService {

    private static final String PREVIEW_FILE_NAME = "preview_document";

    @Autowired
    private ModelService<Serializable> modelService;

    @Autowired
    private FileService fileService;

    /**
     * Generate a document according to the specified template ID and row ID.
     * Extracts template variables to optimize data fetching with targeted fields and SubQueries.
     *
     * @param templateId template ID
     * @param rowId row ID
     * @return generated document fileInfo with download URL
     */
    @Override
    public FileInfo generateDocument(Long templateId, Serializable rowId) {
        DocumentTemplate template = getTemplateById(templateId);
        String modelName = template.getModelName();
        Serializable formattedRowId = IdUtils.formatId(modelName, rowId);
        // Extract variables from template based on template type
        List<String> variables = extractTemplateVariables(template);
        // Build SubQueries for OneToMany fields to support
        // dynamic table row looping in Word templates via LoopRowTableRenderPolicy.
        SubQueries subQueries = buildOneToManySubQueries(modelName);
        // Fetch data with targeted fields and DISPLAY conversion
        Map<String, Object> data = modelService.getById(modelName, formattedRowId, variables, subQueries, ConvertType.DISPLAY)
                .orElseThrow(() -> new IllegalArgumentException("The data of `{0}` does not exist", rowId));
        return generateDocumentByTemplate(template, data);
    }

    /**
     * Generate a document according to the specified template ID and data.
     * The data must be a map.
     *
     * @param templateId template ID
     * @param data the data map to render the document
     * @return generated document fileInfo with download URL
     */
    @Override
    public FileInfo generateDocument(Long templateId, Map<String, Object> data) {
        DocumentTemplate template = getTemplateById(templateId);
        return generateDocumentByTemplate(template, data);
    }

    /**
     * Generate a preview PDF directly from the specified HTML body.
     *
     * @param htmlBody HTML content to preview
     * @return generated preview fileInfo with download URL
     */
    @Override
    public FileInfo generatePreviewDocument(String htmlBody) {
        Assert.notBlank(htmlBody, "The htmlBody of preview document is empty.");
        String renderedHtml = TemplateEngine.render(htmlBody, Collections.emptyMap());
        byte[] pdfBytes = PdfFileGenerator.convertHtmlToPdf(renderedHtml);
        return uploadGeneratedFile(this.modelName, PREVIEW_FILE_NAME, pdfBytes, FileType.PDF);
    }

    /**
     * Generate a preview PDF according to the specified template ID with empty data.
     *
     * @param templateId template ID
     * @return generated preview fileInfo with download URL
     */
    @Override
    public FileInfo generatePreviewTemplate(Long templateId) {
        DocumentTemplate template = getTemplateById(templateId);
        byte[] pdfBytes = buildPreviewPdf(template);
        String previewFileName = StringUtils.hasText(template.getFileName()) ? template.getFileName() : PREVIEW_FILE_NAME;
        return uploadGeneratedFile(template.getModelName(), previewFileName, pdfBytes, FileType.PDF);
    }

    /**
     * Get the document template by ID
     *
     * @param templateId the template ID
     * @return the document template
     */
    private DocumentTemplate getTemplateById(Long templateId) {
        DocumentTemplate template = this.getById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("The document template does not exist"));
        this.validateTemplate(template);
        return template;
    }

    /**
     * Validate the document template
     * @param template the document template
     */
    private void validateTemplate(DocumentTemplate template) {
        Assert.notBlank(template.getModelName(), "The modelName of `{0}` template is empty", template.getFileName());
        if (DocumentTemplateType.RICH_TEXT.equals(template.getTemplateType())) {
            Assert.notBlank(template.getHtmlTemplate(),
                    "The HTML template content of `{0}` template is empty", template.getFileName());
        } else {
            Assert.notNull(template.getFileId(), "The document template file is empty");
        }
    }

    /**
     * Extract template variables based on template type.
     *
     * @param template the document template
     * @return list of variable names extracted from the template
     */
    private List<String> extractTemplateVariables(DocumentTemplate template) {
        DocumentTemplateType templateType = template.getTemplateType();
        if (templateType == null || DocumentTemplateType.WORD.equals(templateType)) {
            // WORD: extract plain text variables from Word file via poi-tl
            try (InputStream is = fileService.downloadStream(template.getFileId())) {
                return WordFileGenerator.extractVariables(is);
            } catch (IOException e) {
                throw new SystemException("Failed to extract variables from Word template.", e);
            }
        } else if (DocumentTemplateType.RICH_TEXT.equals(templateType)) {
            // RICH_TEXT: extract {{xxx}} placeholders from HTML template content via regex
            return PlaceholderUtils.extractVariables(template.getHtmlTemplate());
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Build SubQueries for OneToMany fields of the model.
     * This loads all fields of the related model for each OneToMany field,
     * supporting dynamic table row looping in Word templates via LoopRowTableRenderPolicy.
     *
     * @param modelName the model name
     * @return SubQueries for OneToMany fields
     */
    private SubQueries buildOneToManySubQueries(String modelName) {
        SubQueries subQueries = new SubQueries();
        ModelManager.getModelFieldsWithType(modelName, FieldType.ONE_TO_MANY).forEach(metaField -> {
            String fieldName = metaField.getFieldName();
            subQueries.expand(fieldName, new SubQuery());
        });
        return subQueries;
    }

    /**
     * Generate a document based on template type, dispatching to the appropriate generator.
     *
     * @param template the document template
     * @param data the data map to render the document
     * @return generated document fileInfo with download URL
     */
    private FileInfo generateDocumentByTemplate(DocumentTemplate template, Map<String, Object> data) {
        DocumentTemplateType templateType = template.getTemplateType();
        if (templateType == null || DocumentTemplateType.WORD.equals(templateType)) {
            return generateWordDocument(template, data);
        } else if (DocumentTemplateType.RICH_TEXT.equals(templateType)) {
            return generateRichTextDocument(template, data);
        } else {
            throw new IllegalArgumentException("Unsupported template type: {0}", template.getTemplateType());
        }
    }

    /**
     * Generate a Word document from a WORD template.
     * Renders the template via poi-tl and optionally converts to PDF via docx4j.
     */
    private FileInfo generateWordDocument(DocumentTemplate template, Map<String, Object> data) {
        try (InputStream templateInputStream = fileService.downloadStream(template.getFileId());
             ByteArrayOutputStream docOutputStream = new ByteArrayOutputStream()
        ) {
            WordFileGenerator.renderDocument(templateInputStream, data, docOutputStream);
            FileType fileType = FileType.DOCX;
            byte[] docBytes = docOutputStream.toByteArray();
            if (Boolean.TRUE.equals(template.getConvertToPdf())) {
                fileType = FileType.PDF;
                docBytes = WordFileGenerator.convertDocxToPdf(docBytes);
            }
            return uploadGeneratedFile(template, docBytes, fileType);
        } catch (Exception e) {
            throw new SystemException("Failed to generate the Word document", e);
        }
    }

    /**
     * Generate a PDF document from a RICH_TEXT template.
     * Renders HTML via Pebble and converts to PDF via OpenHTMLToPDF
     */
    private FileInfo generateRichTextDocument(DocumentTemplate template, Map<String, Object> data) {
        // Render HTML from rich text template content using Pebble
        String renderedHtml = TemplateEngine.render(template.getHtmlTemplate(), data);
        // Convert rendered HTML to PDF via OpenHTMLToPDF
        byte[] pdfBytes = PdfFileGenerator.convertHtmlToPdf(renderedHtml);
        return uploadGeneratedFile(template, pdfBytes, FileType.PDF);
    }

    /**
     * Build a preview PDF with empty data for the specified template.
     */
    private byte[] buildPreviewPdf(DocumentTemplate template) {
        if (template.getFileId() != null) {
            FileInfo templateFileInfo = fileService.getByFileId(template.getFileId())
                    .orElseThrow(() -> new IllegalArgumentException("The template file does not exist: {0}",
                            template.getFileId()));
            try (InputStream inputStream = fileService.downloadStream(template.getFileId())) {
                byte[] templateFileBytes = inputStream.readAllBytes();
                return switch (templateFileInfo.getFileType()) {
                    case PDF -> templateFileBytes;
                    case DOCX -> renderWordTemplateToPdf(templateFileBytes, Collections.emptyMap());
                    default -> throw new IllegalArgumentException(
                            "Preview only supports PDF or DOCX template files. Current file type: {0}",
                            templateFileInfo.getFileType().getType());
                };
            } catch (IOException e) {
                throw new SystemException("Failed to load the template file for preview.", e);
            }
        }
        if (StringUtils.hasText(template.getHtmlTemplate())) {
            String renderedHtml = TemplateEngine.render(template.getHtmlTemplate(), Collections.emptyMap());
            return PdfFileGenerator.convertHtmlToPdf(renderedHtml);
        }
        throw new IllegalArgumentException("The template does not contain previewable content.");
    }

    /**
     * Render the DOCX template with the given data and convert it to PDF.
     */
    private byte[] renderWordTemplateToPdf(byte[] templateFileBytes, Map<String, Object> data) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(templateFileBytes);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            WordFileGenerator.renderDocument(inputStream, data, outputStream);
            return WordFileGenerator.convertDocxToPdf(outputStream.toByteArray());
        } catch (IOException e) {
            throw new SystemException("Failed to render the preview DOCX template.", e);
        }
    }

    /**
     * Upload the generated document file to OSS and return the FileInfo.
     *
     * @param template the document template
     * @param fileBytes the generated file content
     * @param fileType the file type (DOCX or PDF)
     * @return FileInfo with download URL
     */
    private FileInfo uploadGeneratedFile(DocumentTemplate template, byte[] fileBytes, FileType fileType) {
        return uploadGeneratedFile(template.getModelName(), template.getFileName(), fileBytes, fileType);
    }

    /**
     * Upload the generated file to OSS and return the FileInfo.
     */
    private FileInfo uploadGeneratedFile(String modelName, String fileName, byte[] fileBytes, FileType fileType) {
        try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            UploadFileDTO uploadFileDTO = new UploadFileDTO();
            uploadFileDTO.setModelName(modelName);
            uploadFileDTO.setFileName(fileName);
            uploadFileDTO.setFileType(fileType);
            // bytes to KB
            uploadFileDTO.setFileSize(fileBytes.length / 1024);
            uploadFileDTO.setInputStream(inputStream);
            return fileService.uploadFromStream(uploadFileDTO);
        } catch (IOException e) {
            throw new SystemException("Failed to upload the generated document", e);
        }
    }
}
