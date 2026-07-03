package io.softa.starter.user.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.BulkAddRequest;
import io.softa.starter.user.dto.BulkAddResult;
import io.softa.starter.user.dto.BulkRevokeRequest;
import io.softa.starter.user.dto.RoleUsersDTO;
import io.softa.starter.user.dto.UserRolePair;
import io.softa.starter.user.dto.UserRolesDTO;
import io.softa.starter.user.enums.RoleSource;
import io.softa.starter.user.service.BulkUserRoleService;
import io.softa.starter.user.service.UserRoleRelService;

/**
 * Bulk user-role assignment endpoints — backs the three Wizard entry points:
 *   A. /admin/user/{userId}/roles/bulk         — single user × N roles
 *   B. /admin/role/{roleId}/users/bulk         — single role × N users
 *   C. /admin/user-role/bulk                   — M users × N roles (matrix)
 *
 * All three pre-filter pairs on the frontend (classifyRoleForUser + bulk
 * decision) and submit the resulting flat pair list. Backend stays compat-
 * unaware; skip reasons are technical only.
 */
@Tag(name = "Admin UserRole")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class UserRoleController {

    private final BulkUserRoleService bulkUserRoleService;
    private final UserRoleRelService userRoleRelService;

    @PostMapping("/user-role/bulk")
    @Operation(summary = "Bulk assign (M users × N roles) — entry C")
    public ApiResponse<BulkAddResult> bulkAdd(@RequestBody @Valid BulkAddRequest body) {
        RoleSource source = body.source() == null ? RoleSource.MANUAL : body.source();
        return ApiResponse.success(bulkUserRoleService.bulkAdd(body.pairs(), source));
    }

    @PostMapping("/user-role/bulk-revoke")
    @Operation(summary = "Bulk revoke user_role_rel rows by id — routed through the typed service "
            + "so it enforces the system-role last-holder guard and evicts affected users' cached permissions")
    public ApiResponse<Void> bulkRevoke(@RequestBody @Valid BulkRevokeRequest body) {
        // Delegates to the typed UserRoleRelService (NOT generic /UserRoleRel/deleteByIds):
        // deleteByIds snapshots affected userIds, runs guardSystemRoleHolderRemoval,
        // then publishes UserRoleRelChangedEvent for AFTER_COMMIT cache eviction.
        userRoleRelService.deleteByIds(body.relIds());
        return ApiResponse.success();
    }

    @PostMapping("/role/{roleId}/users/bulk")
    @Operation(summary = "Single role × N users (entry B) — convenience wrapper around bulkAdd")
    public ApiResponse<BulkAddResult> roleBulkAddUsers(@PathVariable Long roleId,
                                                       @RequestBody @Valid RoleUsersDTO body) {
        List<UserRolePair> pairs = body.userIds().stream()
                .map(userId -> new UserRolePair(userId, roleId))
                .toList();
        return ApiResponse.success(bulkUserRoleService.bulkAdd(pairs, RoleSource.MANUAL));
    }

    @PostMapping("/user/{userId}/roles/bulk")
    @Operation(summary = "Single user × N roles (entry A) — convenience wrapper around bulkAdd")
    public ApiResponse<BulkAddResult> userBulkAddRoles(@PathVariable Long userId,
                                                       @RequestBody @Valid UserRolesDTO body) {
        List<UserRolePair> pairs = body.roleIds().stream()
                .map(roleId -> new UserRolePair(userId, roleId))
                .toList();
        return ApiResponse.success(bulkUserRoleService.bulkAdd(pairs, RoleSource.MANUAL));
    }
}
