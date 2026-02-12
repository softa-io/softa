package io.softa.starter.file.controller;

import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
     * Using ModelAttribute to receive the file object
     *
     * @param importWizard the import wizard containing the file and import settings
     * @return the import result
     */
    @Operation(description = "Import data from the uploaded file")
    @Parameter(name = "importWizard", description = "Form-data containing the file object.", required = true)
    @PostMapping(value = "/dynamicImport")
    public ApiResponse<ImportHistory> importWithoutTemplate(@ModelAttribute ImportWizard importWizard) {
        Assert.isTrue(StringUtils.isNotBlank(importWizard.getFileName()), "File name cannot be empty!");
        Assert.notNull(importWizard.getFile(), "File cannot be empty!");
        Assert.notNull(importWizard.getImportRule(), "ImportRule cannot be null.");
        return ApiResponse.success(importService.importByDynamic(importWizard));
    }

}
