package io.softa.starter.file.service;

import java.io.InputStream;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.entity.ImportHistory;
import io.softa.starter.file.vo.ImportWizard;

public interface ImportService {

    /**
     * Get the fileInfo of the import template by template ID
     *
     * @param templateId template ID
     * @return import template fileInfo
     */
    FileInfo getTemplateFile(Long templateId);

    /**
     * Import data from the uploaded file and the import template ID
     *
     * @param templateId       the ID of the import template
     * @param file             the uploaded file
     * @param env the environment variables
     * @return the import result
     */
    ImportHistory importByTemplate(Long templateId, MultipartFile file, Map<String, Object> env);

    /**
     * Import data from the uploaded file and dynamic import settings
     *
     * @return the import result
     */
    ImportHistory importByDynamic(ImportWizard importWizard);

    /**
     * Validate data from the uploaded file using the import template ID (no persistence).
     * Forces skipException=true to collect all row errors. Generates a result Excel containing
     * all rows (both passed and failed) for complete user feedback.
     *
     * @param templateId the ID of the import template
     * @param file       the uploaded file
     * @param env        the environment variables
     * @return the validation result as ImportHistory
     */
    ImportHistory validateByTemplate(Long templateId, MultipartFile file, Map<String, Object> env);

    /**
     * Validate data from the uploaded file using dynamic import settings (no persistence).
     * Forces skipException=true to collect all row errors. Generates a result Excel containing
     * all rows (both passed and failed) for complete user feedback.
     *
     * @param importWizard the import wizard with dynamic settings
     * @return the validation result as ImportHistory
     */
    ImportHistory validateByDynamic(ImportWizard importWizard);

    /**
     * Synchronous import data from the uploaded file and import template
     *
     * @param importTemplateDTO the import template DTO
     * @param inputStream the input stream of the uploaded file
     * @param importHistory the import history object
     * @return the import history object
     */
    ImportHistory syncImport(ImportTemplateDTO importTemplateDTO, InputStream inputStream, ImportHistory importHistory);

    /**
     * Synchronous validation of data from the uploaded file (no persistence).
     * Forces skipException=true to collect all row errors. Generates a result Excel
     * containing all rows (both passed and failed).
     *
     * @param importTemplateDTO the import template DTO
     * @param inputStream the input stream of the uploaded file
     * @param importHistory the import history object
     * @return the import history object with validation status
     */
    ImportHistory syncValidate(ImportTemplateDTO importTemplateDTO, InputStream inputStream, ImportHistory importHistory);
}
