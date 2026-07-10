package io.softa.starter.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.dto.PermissionInfo;
import io.softa.starter.user.service.PermissionInfoEnricher;

/**
 * /me endpoints — exposes the current user's UI context.
 *
 * Frontend Sidebar / RouteGuard / business pages all consume this single
 * payload via the {@code useUIContext} hook (cached 30s; 401 triggers refetch).
 */
@Tag(name = "Me")
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {

    private final PermissionInfoEnricher permissionInfoEnricher;

    @GetMapping("/uiContext")
    @Operation(summary = "Get the current user's UI context (navigations, permissions, sensitive field sets)")
    public ApiResponse<PermissionInfo> uiContext() {
        Context ctx = ContextHolder.getContext();
        PermissionInfo info = permissionInfoEnricher.enrich(ctx.getTenantId(), ctx.getUserId());
        return ApiResponse.success(info);
    }
}
