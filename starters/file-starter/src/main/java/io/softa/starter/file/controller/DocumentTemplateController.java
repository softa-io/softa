package io.softa.starter.file.controller;

import java.io.Serializable;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.file.dto.DocumentPreviewRequest;
import io.softa.starter.file.entity.DocumentTemplate;
import io.softa.starter.file.service.DocumentTemplateService;

/**
 * DocumentTemplate Model Controller
 */
@Tag(name = "DocumentTemplate")
@RestController
@RequestMapping("/DocumentTemplate")
public class DocumentTemplateController extends EntityController<DocumentTemplateService, DocumentTemplate, Long> {

    /**
     * Generate a word or PDF document according to the specified template ID and row ID.
     *
     * @param templateId template ID
     * @param rowId row ID
     * @return generated document fileInfo with download URL
     */
    @Operation(description = "Generate a word or PDF document according to the specified template ID and row ID.")
    @GetMapping("/generateDocument")
    @Parameters({
            @Parameter(name = "templateId", description = "Template ID"),
            @Parameter(name = "rowId", description = "Data ID of the business data model")
    })
    public ApiResponse<FileInfo> generateDocument(@RequestParam Long templateId,
                                                  @RequestParam Serializable rowId) {
        FileInfo fileInfo = service.generateDocument(templateId, rowId);
        return ApiResponse.success(fileInfo);
    }

    /**
     * Generate a preview PDF directly from the specified HTML body.
     *
     * @param request preview request with htmlBody only
     * @return generated preview fileInfo with download URL
     */
    @Operation(description = "Generate a preview PDF directly from the specified HTML body.")
    @PostMapping("/generatePreviewDocument")
    public ApiResponse<FileInfo> generatePreviewDocument(@RequestBody @Valid DocumentPreviewRequest request) {
        return ApiResponse.success(service.generatePreviewDocument(request.getHtmlBody()));
    }

    /**
     * Generate a preview PDF according to the specified template ID with empty data.
     *
     * @param templateId template ID
     * @return generated preview fileInfo with download URL
     */
    @Operation(description = "Generate a preview PDF according to the specified template ID with empty data.")
    @GetMapping("/generatePreviewTemplate")
    @Parameter(name = "templateId", description = "Template ID")
    public ApiResponse<FileInfo> generatePreviewTemplate(@RequestParam Long templateId) {
        return ApiResponse.success(service.generatePreviewTemplate(templateId));
    }

}
