package io.softa.starter.file.service;

import java.io.Serializable;
import java.util.Map;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.service.EntityService;
import io.softa.starter.file.entity.DocumentTemplate;

/**
 * DocumentTemplate Model Service Interface
 */
public interface DocumentTemplateService extends EntityService<DocumentTemplate, Long> {

    /**
     * Generate a document according to the specified template ID and row ID.
     *
     * @param templateId template ID
     * @param rowId row ID
     * @return generated document fileInfo with download URL
     */
    FileInfo generateDocument(Long templateId, Serializable rowId);

    /**
     * Generate a document according to the specified template ID and data map.
     *
     * @param templateId  template ID
     * @param data the data map to render the document
     * @return generated document fileInfo with download URL
     */
    FileInfo generateDocument(Long templateId, Map<String, Object> data);

    /**
     * Generate a preview PDF directly from the specified HTML body.
     *
     * @param htmlBody HTML content to preview
     * @return generated preview fileInfo with download URL
     */
    FileInfo generatePreviewDocument(String htmlBody);

    /**
     * Generate a preview PDF according to the specified template ID with empty data.
     *
     * @param templateId template ID
     * @return generated preview fileInfo with download URL
     */
    FileInfo generatePreviewTemplate(Long templateId);

}
