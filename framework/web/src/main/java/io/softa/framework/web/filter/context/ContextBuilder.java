package io.softa.framework.web.filter.context;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.enums.Language;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.base.exception.UserNotFoundException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.web.utils.CookieUtils;

@Slf4j
@Component
public class ContextBuilder {

    private final CacheService cacheService;

    public ContextBuilder(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * Get UserInfo from the request based on session ID in cookies or headers.
     *
     * @param request the HTTP request
     * @return the UserInfo associated with the session
     * @throws UserNotFoundException if the session ID is missing or invalid, or if user info is not found
     */
    private UserInfo getUserInfo(HttpServletRequest request) throws UserNotFoundException {
        String sessionId = CookieUtils.getCookie(request, BaseConstant.SESSION_ID);
        if (sessionId == null) {
            // If sessionId is not found in cookies, get it from the request header
            sessionId = request.getHeader(BaseConstant.SESSION_ID_HEADER);
        }

        if (sessionId == null) {
            log.warn("Session ID is missing");
            throw new UserNotFoundException("Session ID is missing");
        }

        String userId = cacheService.get(RedisConstant.SESSION + sessionId, String.class);
        if (userId == null) {
            // Session provided but invalid -> "missing user"
            throw new UserNotFoundException("Invalid session ID");
        }

        UserInfo userInfo = cacheService.get(RedisConstant.USER_INFO + userId, UserInfo.class);
        if (userInfo == null) {
            // Session ID valid but userInfo missing -> also "missing user"
            throw new UserNotFoundException("User info not found for user ID: " + userId);
        }
        return userInfo;
    }

    /**
     * Setup user context with user info.
     * Extract the `debug` parameter from the URI to enable debug mode.
     *
     * @param request the current HTTP request
     */
    public Context buildUserContext(HttpServletRequest request) throws UserNotFoundException {
        // UserInfo userInfo = new UserInfo();
        UserInfo userInfo = this.getUserInfo(request);
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
            this.setMultiTenancyEnv(context, userInfo);
        }
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
        String debug = request.getParameter(BaseConstant.DEBUG);
        if (Boolean.parseBoolean(debug) || "1".equals(debug)) {
            context.setDebug(true);
        }
    }

    /**
     * Set the datasource key for the current thread based on the user info.
     * Used for multi-tenancy applications, the mode of shared app with separate data.
     *
     * @param context the current context
     * @param userInfo the user info
     */
    private void setMultiTenancyEnv(Context context, UserInfo userInfo) {
        Assert.notBlank(userInfo.getTenantId(), "User tenantId cannot be null in multi-tenancy mode.");
        context.setTenantId(userInfo.getTenantId());
    }

}
