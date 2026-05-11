package io.softa.starter.metadata.sequence.controller;

import java.util.List;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.controller.EntityController;
import io.softa.starter.metadata.sequence.entity.SysSequence;
import io.softa.starter.metadata.sequence.service.SysSequenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST binding marker for {@link SysSequence}.
 *
 * <p>Generic CRUD verbs (searchList / getById / updateOne / etc.) are served
 * by the framework's catch-all {@code ModelController} via the
 * {@code /SysSequence/...} URL pattern; this class exists so OpenAPI/Swagger
 * documents the model under a dedicated tag and so request path resolution
 * picks up the {@code @RequestMapping} prefix.
 *
 * <p>v1 admin policy (enforced at config-validation layer, not by removing
 * endpoints): {@code createOne} / {@code deleteById} are forbidden for
 * tenant admins; {@code updateOne} only accepts changes to template /
 * startValue / mode / cadence / description. Tenant bootstrap is done via
 * {@code SysPreDataService.loadPreTenantData} on the JSON files in
 * {@code resources/data-tenant/}.
 */
@Tag(name = "SysSequence")
@RestController
@RequestMapping("/SysSequence")
public class SysSequenceController extends EntityController<SysSequenceService, SysSequence, Long> {

    @PostMapping("/createOne")
    @Operation(summary = "createOne", description = "Create one SysSequence.")
    public ApiResponse<Long> createOne(@RequestBody SysSequence row) {
        return ApiResponse.success(service.createOne(row));
    }

    @PostMapping("/createList")
    @Operation(summary = "createList", description = "Create SysSequence list.")
    public ApiResponse<List<Long>> createList(@RequestBody List<SysSequence> rows) {
        Assert.notEmpty(rows, "rows must not be empty");
        validateBatchSize(rows.size());
        return ApiResponse.success(service.createList(rows));
    }

    @PostMapping("/updateOne")
    @Operation(summary = "updateOne", description = "Update one SysSequence.")
    public ApiResponse<Boolean> updateOne(@RequestBody SysSequence row) {
        Assert.notNull(row.getId(), "`id` cannot be null when updating SysSequence");
        return ApiResponse.success(service.updateOne(row));
    }

    @PostMapping("/updateList")
    @Operation(summary = "updateList", description = "Update SysSequence list.")
    public ApiResponse<Boolean> updateList(@RequestBody List<SysSequence> rows) {
        Assert.notEmpty(rows, "rows must not be empty");
        validateBatchSize(rows.size());
        return ApiResponse.success(service.updateList(rows));
    }

    @PostMapping("/deleteById")
    @Operation(summary = "deleteById", description = "Delete one SysSequence.")
    public ApiResponse<Boolean> deleteById(@RequestBody Long id) {
        return ApiResponse.success(service.deleteById(id));
    }

    @PostMapping("/deleteByIds")
    @Operation(summary = "deleteByIds", description = "Delete SysSequence list.")
    public ApiResponse<Boolean> deleteByIds(@RequestBody List<Long> ids) {
        Assert.notEmpty(ids, "ids must not be empty");
        validateBatchSize(ids.size());
        return ApiResponse.success(service.deleteByIds(ids));
    }
}
