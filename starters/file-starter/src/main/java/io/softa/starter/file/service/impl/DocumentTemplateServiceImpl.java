package io.softa.starter.file.service.impl;

import java.io.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.placeholder.HtmlTemplateRenderer;
import io.softa.framework.base.placeholder.PlaceholderUtils;
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
import io.softa.starter.file.entity.DocumentTemplate;
import io.softa.starter.file.enums.DocumentTemplateType;
import io.softa.starter.file.file.PdfFileGenerator;
import io.softa.starter.file.file.WordFileGenerator;
import io.softa.starter.file.service.DocumentTemplateService;

/**
 * DocumentTemplate Model Service Implementation
 */
@Service
public class DocumentTemplateServiceImpl extends EntityServiceImpl<DocumentTemplate, Long> implements DocumentTemplateService {

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
        // Extract variables from template based on template type
        List<String> variables = extractTemplateVariables(template);
        // Build SubQueries for OneToMany fields to support
        // dynamic table row looping in Word templates via LoopRowTableRenderPolicy.
        SubQueries subQueries = buildOneToManySubQueries(modelName);
        // Fetch data with targeted fields and DISPLAY conversion
        Map<String, Object> data = modelService.getById(modelName, rowId, variables, subQueries, ConvertType.DISPLAY)
                .orElseThrow(() -> new IllegalArgumentException("The data of `{0}` does not exist", rowId));
        return generateDocumentByTemplate(template, data);
    }

    /**
     * Generate a document according to the specified template ID and data object.
     * The data object could be a map or a POJO.
     *
     * @param templateId template ID
     * @param data the data object to render the document
     * @return generated document fileInfo with download URL
     */
    @Override
    public FileInfo generateDocument(Long templateId, Object data) {
        DocumentTemplate template = getTemplateById(templateId);
        return generateDocumentByTemplate(template, data);
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
     * @param data the data object to render the document
     * @return generated document fileInfo with download URL
     */
    private FileInfo generateDocumentByTemplate(DocumentTemplate template, Object data) {
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
    private FileInfo generateWordDocument(DocumentTemplate template, Object data) {
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
     * Renders HTML via FreeMarker and converts to PDF via OpenPDF.
     */
    private FileInfo generateRichTextDocument(DocumentTemplate template, Object data) {
        // Render HTML from rich text template content using FreeMarker
        String renderedHtml = HtmlTemplateRenderer.render(template.getHtmlTemplate(), data);
        // Convert rendered HTML to PDF via OpenPDF
        byte[] pdfBytes = PdfFileGenerator.convertHtmlToPdf(renderedHtml);
        return uploadGeneratedFile(template, pdfBytes, FileType.PDF);
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
        try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            UploadFileDTO uploadFileDTO = new UploadFileDTO();
            uploadFileDTO.setModelName(template.getModelName());
            uploadFileDTO.setFileName(template.getFileName());
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
