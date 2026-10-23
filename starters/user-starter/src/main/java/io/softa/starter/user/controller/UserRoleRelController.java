package io.softa.starter.user.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.BulkAddRequest;
import io.softa.starter.user.dto.BulkAddResult;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.UserRoleSource;
import io.softa.starter.user.service.BulkUserRoleService;
import io.softa.starter.user.service.UserRoleRelService;

/**
 * UserRoleRel model controller.
 *
 * <p>Overrides {@code /deleteById} / {@code /deleteByIds} so they route through
 * the typed {@link UserRoleRelService} (system-role last-holder guard + per-user
 * cache eviction via {@code UserRoleRelChangedEvent}) instead of the generic
 * {@code ModelController} path, which skips both. Also hosts the bulk assign
 * ({@code /bulkAdd} — M users × N roles flattened to pairs, partial-success /
 * dedup / cross-tenant validation via {@link BulkUserRoleService}). Reads fall
 * through to the generic controller.
 */
@Tag(name = "UserRoleRel")
@RestController
@RequestMapping("/UserRoleRel")
public class UserRoleRelController
        extends EntityController<UserRoleRelService, UserRoleRel, Long> {

    @Autowired
    private BulkUserRoleService bulkUserRoleService;

    @Operation(summary = "deleteById — typed (last-holder guard + cache eviction)")
    @PostMapping("/deleteById")
    public ApiResponse<Boolean> deleteById(@RequestParam Long id) {
        return ApiResponse.success(service.deleteById(id));
    }

    @Operation(summary = "deleteByIds — typed (last-holder guard + cache eviction)")
    @PostMapping("/deleteByIds")
    public ApiResponse<Boolean> deleteByIds(@RequestParam List<Long> ids) {
        return ApiResponse.success(service.deleteByIds(ids));
    }

    @Operation(summary = "Bulk assign (M users × N roles) — partial-success per pair; "
            + "returns added / skipped with technical reasons")
    @PostMapping("/bulkAdd")
    public ApiResponse<BulkAddResult> bulkAdd(@RequestBody @Valid BulkAddRequest body) {
        UserRoleSource source = body.source() == null ? UserRoleSource.MANUAL : body.source();
        return ApiResponse.success(bulkUserRoleService.bulkAdd(body.pairs(), source));
    }

    @Operation(summary = "createOne — typed (runs the SUPER_ADMIN bind guard); the generic "
            + "/UserRoleRel/createOne path skips it")
    @PostMapping("/createOne")
    public ApiResponse<Long> createOne(@RequestBody UserRoleRel entity) {
        return ApiResponse.success(service.createOne(entity));
    }

    @Operation(summary = "createList — typed, like createOne: the bind guards run and the holders' caches "
            + "are cleared")
    @PostMapping("/createList")
    public ApiResponse<List<Long>> createList(@RequestBody List<UserRoleRel> entities) {
        return ApiResponse.success(service.createList(entities));
    }

    // The generic variants below wrote memberships past the typed service: no cache was cleared, so a
    // revoked role kept working for up to an hour, and a membership moved to another role in place
    // went around the guard that keeps a tenant's last administrator. A membership is added and removed;
    // it is not edited.

    @PostMapping("/createOneAndFetch")
    public ApiResponse<Void> createOneAndFetch(@RequestBody Object ignored) {
        throw notHere("created through createOne or bulkAdd");
    }

    @PostMapping("/createListAndFetch")
    public ApiResponse<Void> createListAndFetch(@RequestBody Object ignored) {
        throw notHere("created through createList or bulkAdd");
    }

    @PostMapping({"/updateOne", "/updateOneAndFetch", "/updateList", "/updateListAndFetch", "/updateByFilter"})
    public ApiResponse<Void> update(@RequestBody Object ignored) {
        throw notHere("not edited in place: remove it and add the new one");
    }

    private static PermissionException notHere(String how) {
        return new PermissionException("A role membership is " + how + ".");
    }
}
