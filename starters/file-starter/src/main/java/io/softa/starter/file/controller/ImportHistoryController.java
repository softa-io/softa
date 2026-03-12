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
import io.softa.starter.file.entity.ImportHistory;
import io.softa.starter.file.service.ImportHistoryService;

/**
 * ImportHistoryController
 */
@Tag(name = "Import History")
@RestController
@RequestMapping("/ImportHistory")
public class ImportHistoryController extends EntityController<ImportHistoryService, ImportHistory, Long> {

    /**
     * List current user's import history of the specified model.
     *
     * @param modelName model name
     * @return import history list
     */
    @Operation(summary = "myImportHistory", description = "List current user's import history of the specified model")
    @PostMapping(value = "/myImportHistory")
    public ApiResponse<List<Map<String, Object>>> myImportHistory(@RequestParam String modelName) {
        return ApiResponse.success(service.listMyImportHistory(modelName));
    }
}
