package io.softa.starter.file.controller;

import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.file.entity.ImportHistory;
import io.softa.starter.file.service.ImportService;
import io.softa.starter.file.vo.ImportWizard;

/**
 * ImportController
 */
@Tag(name = "Import")
@RestController
@RequestMapping("/import")
public class ImportController {

    @Autowired
    private ImportService importService;

    /**
     * Import data from the uploaded file and the import template ID
     *
     * @param templateId       the ID of the import template
     * @param file             the uploaded file
     * @return the import result
     */
    @Operation(description = "Import data from the uploaded file")
    @PostMapping(value = "/importByTemplate")
    public ApiResponse<ImportHistory> importByTemplate(@RequestParam(name = "templateId") Long templateId,
                                                       @RequestParam(name = "file") MultipartFile file,
                                                       @RequestParam(name = "env") String jsonEnv) {
        String fileName = file.getOriginalFilename();
        Assert.isTrue(StringUtils.isNotBlank(fileName), "File name cannot be empty!");
        Assert.notNull(file, "File cannot be empty!");
        Map<String, Object> env = null;
        if (StringUtils.isNotBlank(jsonEnv)) {
            try {
                env = JsonUtils.stringToObject(jsonEnv, new TypeReference<>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("The string of environment variables must be in JSON format: {0}", jsonEnv, e);
            }
        }
        return ApiResponse.success(importService.importByTemplate(templateId, file, env));
    }

    /**
     * Import data from the uploaded file and dynamic import settings
     * Using RequestPart to receive the file object and JSON payload
     *
     * @param file         the uploaded file
     * @param importWizard the import wizard JSON payload
     * @return the import result
     */
    @Operation(description = "Dynamic import from the uploaded file")
    @Parameter(name = "file", description = "Uploaded file part.", required = true)
    @Parameter(name = "wizard", description = "JSON part containing import settings.", required = true)
    @PostMapping(value = "/dynamicImport", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportHistory> dynamicImport(@RequestPart("file") MultipartFile file,
                                                    @RequestPart("wizard") ImportWizard importWizard) {
        importWizard.setFile(file);
        Assert.notNull(importWizard.getFile(), "File cannot be empty!");
        Assert.notNull(importWizard.getImportRule(), "ImportRule cannot be null.");
        Assert.notEmpty(importWizard.getImportFieldDTOList(), "Import fields cannot be empty.");
        return ApiResponse.success(importService.importByDynamic(importWizard));
    }

    /**
     * Validate import data from the uploaded file without persisting.
     * Supports both template import (by templateId) and dynamic import (by ImportWizard).
     *
     * <p>Template import: provide templateId and file as request parameters.
     * <p>Dynamic import: provide file and wizard as multipart form data parts.
     *
     * @param templateId   (optional) the ID of the import template for template-based validation
     * @param file         the uploaded file
     * @param jsonEnv      (optional) environment variables in JSON format, used with template import
     * @param importWizard (optional) the import wizard JSON payload for dynamic validation
     * @return the validation result as ImportHistory (with validation status, failed file, etc.)
     */
    @Operation(description = "Validate import data from the uploaded file without persisting")
    @PostMapping(value = "/validateImport", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportHistory> validateImport(
            @RequestParam(name = "templateId", required = false) Long templateId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "env", required = false) String jsonEnv,
            @RequestPart(name = "wizard", required = false) ImportWizard importWizard) {
        String fileName = file.getOriginalFilename();
        Assert.isTrue(StringUtils.isNotBlank(fileName), "File name cannot be empty!");
        Assert.notNull(file, "File cannot be empty!");
        if (templateId != null) {
            // Template-based validation
            Map<String, Object> env = null;
            if (StringUtils.isNotBlank(jsonEnv)) {
                try {
                    env = JsonUtils.stringToObject(jsonEnv, new TypeReference<>() {});
                } catch (Exception e) {
                    throw new IllegalArgumentException("The string of environment variables must be in JSON format: {0}", jsonEnv, e);
                }
            }
            return ApiResponse.success(importService.validateByTemplate(templateId, file, env));
        } else if (importWizard != null) {
            // Dynamic validation
            importWizard.setFile(file);
            Assert.notNull(importWizard.getImportRule(), "ImportRule cannot be null.");
            Assert.notEmpty(importWizard.getImportFieldDTOList(), "Import fields cannot be empty.");
            return ApiResponse.success(importService.validateByDynamic(importWizard));
        } else {
            throw new IllegalArgumentException("Either templateId or wizard must be provided for validation.");
        }
    }

}
