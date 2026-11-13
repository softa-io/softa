package io.softa.starter.user.controller;

import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.EmpInfo;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.meta.WithheldFields;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.EntitlementService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.dto.MeCredentialRequests.ChangePasswordBody;
import io.softa.starter.user.dto.MeCredentialRequests.CheckNewMobileBody;
import io.softa.starter.user.dto.MeCredentialRequests.CodeBody;
import io.softa.starter.user.dto.MeCredentialRequests.ConfirmMobileBody;
import io.softa.starter.user.dto.MeCredentialRequests.TicketBody;
import io.softa.starter.user.dto.MeCredentialRequests.TicketView;
import io.softa.starter.user.dto.MeCredentialRequests.VerifyCurrentBody;
import io.softa.starter.user.dto.UiContext;
import io.softa.starter.user.service.MyCredentialService;
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
 *
 * <p>Also the signed-in person changing their own password and login mobile ({@link MyCredentialService}). Each
 * acts on the caller only, so the application lists {@code /me/**} under its authenticated-bypass patterns; a
 * change is proved step by step, each step handing back a ticket that only the next step accepts.
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
    /** The data plane's own answer for which sensitive fields the caller never sees; absent in a
     *  deployment without a permission engine, which then hides nothing. */
    private final ObjectProvider<PermissionService> permissionServiceProvider;
    private final MyCredentialService credentialService;

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
        appendEmployeeIdentity(info, ctx);
        appendHiddenFields(info);
        return ApiResponse.success(info);
    }

    /**
     * Add the fields the caller can never see, per model.
     *
     * <p>Asked of the permission engine on every request rather than read from the cached snapshot:
     * it is the same computation the write guard refuses by, so the screen and the server cannot
     * disagree about it. Only field names leave the server — not the set catalogue behind them.
     *
     * <p>Besides the sensitive fields, the relations onto a model the caller can read nothing of
     * ({@link WithheldFields}): the metadata leaves those out too, and a screen takes a declared field
     * the metadata lacks for a mistake unless this list names it.
     */
    private void appendHiddenFields(UiContext info) {
        PermissionService permissionService = permissionServiceProvider.getIfAvailable();
        Map<String, Set<String>> hidden = permissionService == null ? null : permissionService.getHiddenSensitiveFields();
        info.setModelHiddenFieldsMap(hidden == null ? Map.of() : WithheldFields.withRelations(hidden));
    }

    /**
     * Add the caller's own employee id.
     *
     * <p>Read from the context — {@code EmployeeContextEnricher} resolved it for this request — rather
     * than from the cached snapshot, so linking an account to an employee takes effect on the next
     * request instead of after the snapshot's hour.
     */
    private void appendEmployeeIdentity(UiContext info, Context ctx) {
        EmpInfo empInfo = ctx == null ? null : ctx.getEmpInfo();
        info.setEmpId(empInfo == null ? null : empInfo.getEmpId());
    }

    /**
     * Add the tenant's entitled module ids to the uiContext (版本计费). The frontend uses it to
     * filter the role wizard (and, later, to gate navigation). Omitted entirely when no
     * {@link EntitlementService} is installed — the frontend then applies no version filtering.
     */
    private void appendEntitlement(UiContext info, Long tenantId) {
        // Platform SUPER_ADMIN is NOT entitlement-scoped: it operates above any single tenant, so
        // entitledModules(tenantId) resolves to the floor plan (no subscribed tenant in context) and the
        // frontend would wrongly narrow the role wizard / sidebar to the floor's modules — down to nothing
        // at all on a deployment whose floor is a zero-module plan. Leave entitledModules unset so the
        // frontend applies NO version filtering (every module visible) — same as the
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

    @PostMapping("/password/sendCode")
    @Operation(summary = "Send a code to the login mobile (changing the password, or proving it is you before changing the mobile)")
    public ApiResponse<Void> sendCode() {
        credentialService.sendCurrentMobileCode();
        return ApiResponse.success();
    }

    @PostMapping("/password/verifyCode")
    @Operation(summary = "Check the code; the ticket opens /password/change for ten minutes")
    public ApiResponse<TicketView> verifyPasswordCode(@RequestBody CodeBody body) {
        return ApiResponse.success(new TicketView(credentialService.verifyCodeForPassword(body.code())));
    }

    @PostMapping("/password/change")
    @Operation(summary = "Set a new password; every other device is signed out")
    public ApiResponse<Void> changePassword(@RequestBody ChangePasswordBody body) {
        credentialService.changePassword(body.ticket(), body.newPassword());
        return ApiResponse.success();
    }

    @PostMapping("/mobile/verifyCurrent")
    @Operation(summary = "Prove it is you with the code sent to the login mobile, or your password")
    public ApiResponse<TicketView> verifyCurrent(@RequestBody VerifyCurrentBody body) {
        return ApiResponse.success(new TicketView(
                credentialService.verifyCurrentForMobile(body.method(), body.code(), body.password())));
    }

    @PostMapping("/mobile/checkNew")
    @Operation(summary = "Name the new login mobile; a code is sent to it")
    public ApiResponse<TicketView> checkNew(@RequestBody CheckNewMobileBody body) {
        return ApiResponse.success(new TicketView(credentialService.checkNewMobile(body.ticket(), body.newMobile())));
    }

    @PostMapping("/mobile/resendCode")
    @Operation(summary = "Send the new number another code; the ticket stays the same")
    public ApiResponse<Void> resendCode(@RequestBody TicketBody body) {
        credentialService.resendNewMobileCode(body.ticket());
        return ApiResponse.success();
    }

    @PostMapping("/mobile/confirm")
    @Operation(summary = "Confirm the new login mobile; it holds in every company, every other device is signed out")
    public ApiResponse<Void> confirm(@RequestBody ConfirmMobileBody body) {
        credentialService.confirmNewMobile(body.ticket(), body.code());
        return ApiResponse.success();
    }
}
