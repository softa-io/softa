package io.softa.starter.user.controller;

import java.util.Set;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.EntitlementService;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.dto.UiContext;
import io.softa.starter.user.service.impl.UiContextBuilder;
import io.softa.starter.user.util.PermissionSnapshotKey;

/**
 * /me endpoints — the current user's UI context.
 *
 * <p>Reads the permission snapshot from the shared cache into a typed {@link UiContext} (the engine —
 * permission-starter — is the authoritative builder + writer; user-starter deserializes the cached
 * JSON into its OWN {@code UiContext} DTO, never referencing the engine's {@code PermissionInfo} type,
 * so ⊥ holds). On a cache MISS (Redis blip, or the engine cache not yet warmed by a gated request),
 * falls back to {@link UiContextBuilder}, which assembles the same {@code UiContext} from user-starter's
 * OWN RBAC entities — so {@code /me/uiContext} is self-sufficient and never returns a bare {@code null}
 * to the bootstrap.
 *
 * <p>Frontend Sidebar / RouteGuard / business pages all consume this single
 * payload via the {@code useUIContext} hook (cached 30s; 401 triggers refetch).
 */
@Tag(name = "Me")
@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {

    private final CacheService cacheService;
    private final UiContextBuilder uiContextBuilder;
    /** Optional — only present when tenant-starter (entitlement) is installed; null = pure
     *  RBAC, no version gating (frontend then treats every module as entitled). */
    private final ObjectProvider<EntitlementService> entitlementServiceProvider;

    @GetMapping("/uiContext")
    @Operation(summary = "Get the current user's UI context (navigations, permissions, sensitive field sets)")
    public ApiResponse<UiContext> uiContext() {
        Context ctx = ContextHolder.getContext();
        UiContext info = cacheService.get(
                PermissionSnapshotKey.forUser(ctx.getTenantId(), ctx.getUserId()), UiContext.class);
        if (info == null) {
            // Engine cache cold — build the same shape from user-starter's own
            // RBAC entities so the bootstrap always gets a payload.
            info = uiContextBuilder.build(ctx.getUserId());
        }
        appendEntitlement(info, ctx.getTenantId());
        return ApiResponse.success(info);
    }

    /**
     * Add the tenant's entitled module ids to the uiContext (版本计费). The frontend uses it to
     * filter the role wizard (and, later, to gate navigation). Omitted entirely when no
     * {@link EntitlementService} is installed — the frontend then applies no version filtering.
     */
    private void appendEntitlement(UiContext info, Long tenantId) {
        // Platform SUPER_ADMIN is NOT entitlement-scoped: it operates above any single tenant, so
        // entitledModules(tenantId) resolves to the Free floor (no paid tenant in context) and the
        // frontend would wrongly narrow the role wizard / sidebar to Free's modules. Leave entitledModules
        // unset so the frontend applies NO version filtering (every module visible) — same as the
        // no-EntitlementService case.
        if (info.getRoleCodes() != null && info.getRoleCodes().contains(RoleConstant.CODE_SUPER_ADMIN)) {
            return;
        }
        EntitlementService entitlementService = entitlementServiceProvider.getIfAvailable();
        if (entitlementService == null) {
            return;
        }
        Set<String> modules = entitlementService.entitledModules(tenantId);
        info.setEntitledModules(modules != null ? modules : Set.of());
    }
}
