package io.softa.framework.web.filter.context;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.enums.Language;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.base.exception.UserNotFoundException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.framework.orm.service.UserInfoService;
import io.softa.framework.web.session.ReentryTokenIssuer;
import io.softa.framework.web.session.SessionErrorCodes;
import io.softa.framework.web.session.SessionRecord;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.framework.web.session.SessionStore;

@Slf4j
@Component
public class ContextBuilder implements SmartInitializingSingleton {

    @Autowired
    private CacheService cacheService;

    @Autowired
    private SessionStore sessionStore;

    @Autowired(required = false)
    private TenantInfoService tenantInfoService;

    /**
     * Rebuilds a cold {@code user-info:} entry. Optional the same way {@link TenantInfoService} is:
     * a deployment without user-starter has no UserInfo to build, and degrades to rejecting the
     * request — which is what it did unconditionally before.
     */
    @Autowired(required = false)
    private UserInfoService userInfoService;

    @Autowired(required = false)
    private List<ContextEnricher> contextEnrichers;

    /** Lazily resolved: the login module that implements it reaches back into the web layer. */
    @Autowired
    private ObjectProvider<ReentryTokenIssuer> reentryTokenIssuer;

    private UserInfo getUserInfo(Long userId) throws UserNotFoundException {
        UserInfo userInfo = cacheService.get(RedisConstant.USER_INFO + userId, UserInfo.class);
        if (userInfo == null && userInfoService != null) {
            // A cold entry is the NORMAL state after any of this codebase's evictions — an invitee
            // activating, a bulk unfreeze, a person renaming themselves, an HR-side employee
            // change. Each of those is written as a refresh and documents the stale value it means
            // to replace; reading through to the database is what makes them refreshes. Treating
            // the miss as "missing user" instead turned every one of them into a forced logout of
            // the very user being updated, with no way back in: the endpoint that could rebuild the
            // entry sits behind this same gate, so only a fresh login could.
            userInfo = userInfoService.loadUserInfo(userId);
        }
        if (userInfo == null) {
            // Nothing behind the id — the session names a user that no longer exists.
            throw sessionExpired("User info not found for user ID: " + userId);
        }
        return userInfo;
    }

    private static UserNotFoundException sessionExpired(String message) {
        UserNotFoundException e = new UserNotFoundException(message);
        e.withErrorCode(SessionErrorCodes.SESSION_EXPIRED);
        return e;
    }

    /**
     * End this session for an organisation that cannot be entered any more, and say so with a fresh
     * sign-in token in {@code errorDetails.authToken}: the person is still who they were, and with it
     * the client offers their other organisations instead of a login screen.
     */
    private UserNotFoundException orgUnavailable(HttpServletRequest request, String sessionId,
                                                 SessionRecord session, String message) {
        this.revokeWithRequest(request, sessionId, SessionRevokeReason.ORG_UNAVAILABLE);
        UserNotFoundException e = new UserNotFoundException(message);
        e.withErrorCode(SessionErrorCodes.ORG_UNAVAILABLE);
        ReentryTokenIssuer issuer = reentryTokenIssuer == null ? null : reentryTokenIssuer.getIfAvailable();
        String token = issuer == null || session.profileId() == null ? null
                : issuer.issueReentryToken(session.profileId());
        if (token != null) {
            e.withErrorDetail("authToken", token);
        }
        return e;
    }

    /**
     * Revoke with the request bound. This filter runs ahead of Spring's request-context filter, and
     * the listeners of the revocation (the login history) read the client's address and agent from
     * the bound request — unbound, the row is written without them.
     */
    private void revokeWithRequest(HttpServletRequest request, String sessionId, SessionRevokeReason reason) {
        if (RequestContextHolder.getRequestAttributes() != null) {
            sessionStore.revoke(sessionId, reason);
            return;
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            sessionStore.revoke(sessionId, reason);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /**
     * Setup user context with user info.
     * Extract the `debug` parameter or debug headers from the request to enable debug mode.
     *
     * @param request the current HTTP request
     */
    public Context buildUserContext(HttpServletRequest request) throws UserNotFoundException {
        String sessionId = SessionStore.sessionIdOf(request);
        if (sessionId == null) {
            log.warn("Session ID is missing");
            throw sessionExpired("Session ID is missing");
        }
        // Reading the session also slides it: seven days after the last request, not after login.
        SessionRecord session = sessionStore.touch(sessionId)
                .orElseThrow(() -> sessionExpired("Invalid session ID"));
        UserInfo userInfo = this.getUserInfo(session.accountId());
        // The account now belongs to someone else: closed, reopened and joined by another person. A
        // session opened for the person before stays theirs and never becomes the new one's. Read
        // from the cached UserInfo, which every write to the account evicts, so no query is added.
        if (userInfo.getProfileId() != null && session.profileId() != null
                && !userInfo.getProfileId().equals(session.profileId())) {
            this.revokeWithRequest(request, sessionId, SessionRevokeReason.UNBOUND);
            throw sessionExpired("The account now belongs to another person; session terminated.");
        }
        // Account lifecycle gate: a session outlives credential entry, so re-check that
        // the account is still active on every request (symmetric to the tenant gate in
        // setMultiTenancyEnv). A Frozen / Deactivated account loses THIS session only — the
        // person's sessions in other tenants are untouched — and the client is told the
        // organisation is unavailable rather than that it must log in again, so it can offer
        // the person's other companies. A password lock is not inactivity: the session stays.
        // active == null is a legacy entry cached before this field existed — left alone.
        if (Boolean.FALSE.equals(userInfo.getActive())) {
            throw this.orgUnavailable(request, sessionId, session, "Account is not active; session terminated.");
        }
        // Create Context with TraceID from the request header
        String traceId = request.getHeader(BaseConstant.X_B3_TRACEID);
        Context context = new Context(traceId);
        context.setUserId(userInfo.getUserId());
        context.setName(userInfo.getName());
        Language language = this.getCurrentLanguage(request, userInfo.getLanguage());
        context.setLanguage(language);
        context.setTimezone(userInfo.getTimezone());
        context.setUserInfo(userInfo);
        if (SystemConfig.env.isEnableMultiTenancy()) {
            this.setMultiTenancyEnv(request, context, userInfo, sessionId, session);
        }
        // No company or country is read from the request. Which companies and countries a request may
        // see is decided by the caller's grant (bridged onto the context by the permission layer), not
        // by a header; a header-driven selection existed once and went with the header switcher.
        // HTTP requests for users are never allowed to use cross-tenant mode
        context.setCrossTenant(false);
        this.setDebugModeFromRequest(request, context);
        // Allow business modules to enrich the context (e.g., EmpInfo, PermissionInfo)
        // Bind the just-built context before enrichers run; enrichers may execute ORM queries
        // that rely on ContextHolder for tenant filtering.
        ContextHolder.runWith(context, () -> this.enrichContext(context));
        return context;
    }

    private void enrichContext(Context context) {
        if (contextEnrichers == null || contextEnrichers.isEmpty()) {
            return;
        }
        for (ContextEnricher enricher : contextEnrichers) {
            try {
                enricher.enrich(context);
            } catch (Exception e) {
                log.error("ContextEnricher [{}] failed for userId={}: {}",
                        enricher.getClass().getSimpleName(), context.getUserId(), e.getMessage());
            }
        }
    }

    /**
     * Build a context for service-to-service calls that authenticate via request
     * signing rather than a logged-in user (e.g. studio → runtime metadata upgrade).
     * No user identity is bound; tenant filtering is bypassed via {@code crossTenant}
     * because the calling service may target tenants the request itself didn't name.
     * TraceId/language/debug are still propagated so the downstream handler keeps
     * the same observability surface as a normal request.
     */
    public Context buildServiceContext(HttpServletRequest request) {
        String traceId = request.getHeader(BaseConstant.X_B3_TRACEID);
        Context context = new Context(traceId);
        Language language = this.getCurrentLanguage(request, null);
        context.setLanguage(language);
        String timezone = request.getHeader("X-Timezone");
        if (StringUtils.isNotBlank(timezone)) {
            context.setTimezone(Timezone.of(timezone));
        }
        context.setCrossTenant(true);
        context.setSkipPermissionCheck(true);
        this.setDebugModeFromRequest(request, context);
        return context;
    }

    /**
     * Setup context for anonymous users or requests that do not require permission check.
     * Extract language from request headers or query params, and timezone from customized request headers.
     *
     * @param request  the current HTTP request
     */
    public Context buildAnonymousContext(HttpServletRequest request) {
        Context context = new Context();
        Language language = this.getCurrentLanguage(request, null);
        context.setLanguage(language);
        String timezone = request.getHeader("X-Timezone");
        if (StringUtils.isNotBlank(timezone)) {
            context.setTimezone(Timezone.of(timezone));
        }
        this.setDebugModeFromRequest(request, context);
        // HTTP requests for non-login users are set to use cross-tenant mode
        context.setCrossTenant(true);
        return context;
    }

    /**
     * Extract language from user info, query params, request headers or default language.
     * LanguageCode from the URI params will override the language from the request headers.
     * For example, `?language=zh-CN` will set the language to Chinese.
     * request.getLocale() will be used if no language is specified, which is based on the Accept-Language header.
     * `zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7` will be parsed as `zh-CN`, which is the highest priority language.
     *
     * @param request the current HTTP request
     * @return the languageCode extracted from the request
     */
    private Language getCurrentLanguage(HttpServletRequest request, Language userLanguage) {
        if (userLanguage != null) {
            return userLanguage;
        }
        String languageCode = request.getParameter("language");
        if (StringUtils.isNotBlank(languageCode)) {
            return Language.of(languageCode);
        } else if (StringUtils.isNotBlank(request.getHeader("Accept-Language"))) {
            languageCode = request.getLocale().toLanguageTag();
            return Language.of(languageCode);
        } else if (StringUtils.isNotBlank(SystemConfig.env.getDefaultLanguage())) {
            return Language.of(SystemConfig.env.getDefaultLanguage());
        }
        return null;
    }

    /**
     * Extract the `debug` parameter from the URI to enable debug mode.
     *
     * @param request the current HTTP request
     * @param context the current context
     */
    private void setDebugModeFromRequest(HttpServletRequest request, Context context) {
        if (isDebugEnabled(request.getParameter(BaseConstant.DEBUG))
                || isDebugEnabled(request.getHeader(BaseConstant.DEBUG))
                || isDebugEnabled(request.getHeader(BaseConstant.X_DEBUG))) {
            context.setDebug(true);
        }
    }

    private boolean isDebugEnabled(String debug) {
        return Boolean.parseBoolean(debug) || "1".equals(debug);
    }

    /**
     * Boot-time invariant: when multi-tenancy is enabled, a {@link TenantInfoService}
     * provider (tenant-starter) MUST be on the classpath. Enforced once after all singletons
     * are initialized, so a missing provider fails fast with a clear message instead of
     * surfacing per-request as a misleading "tenant not active" rejection in
     * {@link #setMultiTenancyEnv}.
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (SystemConfig.env.isEnableMultiTenancy() && tenantInfoService == null) {
            throw new IllegalStateException(
                    "system.enable-multi-tenancy=true but no TenantInfoService bean is present; "
                            + "add tenant-starter to the classpath.");
        }
    }

    /**
     * Set the datasource key for the current thread based on the user info.
     * Used for multi-tenancy applications, the mode of shared app with separate data.
     *
     * @param context the current context
     * @param userInfo the user info
     */
    private void setMultiTenancyEnv(HttpServletRequest request, Context context, UserInfo userInfo,
                                    String sessionId, SessionRecord session) {
        Long tenantId = userInfo.getTenantId();
        Assert.notNull(tenantId, "User tenantId cannot be null in multi-tenancy mode.");
        // Tenant lifecycle gate: only ACTIVE tenants may operate. When a tenant leaves ACTIVE,
        // TenantInfoService.deactivate() evicts the cache so isTenantActive() flips immediately;
        // here we force-logout the user — drop the session and bounce to re-login (fail-closed:
        // a missing provider in multi-tenancy mode also rejects rather than letting the user in).
        if (tenantInfoService == null || !tenantInfoService.isTenantActive(tenantId)) {
            throw this.orgUnavailable(request, sessionId, session,
                    "Tenant " + tenantId + " is not active or cannot be verified; session terminated.");
        }
        context.setTenantId(tenantId);
    }

}
