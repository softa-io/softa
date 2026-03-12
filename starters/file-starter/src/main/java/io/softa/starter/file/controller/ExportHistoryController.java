package io.softa.starter.file.controller;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.file.entity.ExportHistory;
import io.softa.starter.file.service.ExportHistoryService;

/**
 * ExportHistoryController
 */
@Tag(name = "Export History")
@RestController
@RequestMapping("/ExportHistory")
public class ExportHistoryController extends EntityController<ExportHistoryService, ExportHistory, Long> {

    /**
     * List current user's export history of the specified model.
     *
     * @param modelName model name
     * @return export history list
     */
    @Operation(summary = "myExportHistory", description = "List current user's export history of the specified model")
    @PostMapping(value = "/myExportHistory")
    public ApiResponse<List<Map<String, Object>>> myExportHistory(@RequestParam String modelName) {
        return ApiResponse.success(service.listMyExportHistory(modelName));
    }
}
