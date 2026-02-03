package io.softa.starter.file.service;

import java.io.Serializable;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.service.EntityService;
import io.softa.starter.file.entity.DocumentTemplate;

/**
 * DocumentTemplate Model Service Interface
 */
public interface DocumentTemplateService extends EntityService<DocumentTemplate, String> {

    /**
     * Generate a document according to the specified template ID and row ID.
     *
     * @param templateId template ID
     * @param rowId row ID
     * @return generated document fileInfo with download URL
     */
    FileInfo generateDocument(String templateId, Serializable rowId);

    /**
     * Generate a document according to the specified template ID and data object.
     * The data object could be a map or a POJO.
     *
     * @param templateId  template ID
     * @param data the data object to render the document
     * @return generated document fileInfo with download URL
     */
    FileInfo generateDocument(String templateId, Object data);

}