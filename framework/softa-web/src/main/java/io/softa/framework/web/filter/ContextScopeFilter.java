package io.softa.framework.web.filter;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.MdcKeys;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.exception.BaseException;
import io.softa.framework.base.exception.UserNotFoundException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.web.enums.IdentifyType;
import io.softa.framework.web.filter.context.ContextBuilder;
import io.softa.framework.web.filter.context.IdentityResolver;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.response.ApiResponseErrorDetails;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ContextScopeFilter implements Filter {

    private final ContextBuilder contextBuilder;
    private final IdentityResolver identityResolver;

    public ContextScopeFilter(ContextBuilder contextBuilder, IdentityResolver identityResolver) {
        this.contextBuilder = contextBuilder;
        this.identityResolver = identityResolver;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;
        String path = httpReq.getRequestURI();

        IdentifyType identifyRequired = identityResolver.getIdentifyRequired(path);
        if (IdentifyType.USER.equals(identifyRequired)) {
            try {
                Context userContext = contextBuilder.buildUserContext(httpReq);
                runInScope(userContext, request, response, chain);
            } catch (UserNotFoundException e) {
                // Must-login endpoint but no user => legacy behavior: USER_NOT_FOUND JSON
                log.warn("UserNotFoundException: {}", e.getMessage());
                this.userNotFound(httpRes, e);
            } catch (BaseException e) {
                log.error("Failed to build user context", e);
                this.responseError(httpRes, e.getResponseCode(), e.getMessage());
            }
        } else if (IdentifyType.ANONYMOUS.equals(identifyRequired)) {
            Context anonymousContext = contextBuilder.buildAnonymousContext(httpReq);
            runInScope(anonymousContext, request, response, chain);
        } else if (IdentifyType.INTERNAL.equals(identifyRequired) || IdentifyType.OPERATION.equals(identifyRequired)) {
            Context serviceContext = contextBuilder.buildServiceContext(httpReq);
            runInScope(serviceContext, request, response, chain);
        } else {
            // IdentifyType.NONE / OPENAPI, skip context binding
            chain.doFilter(request, response);
        }
    }

    /**
     * Run the filter chain within the given Context scope.
     */
    private void runInScope(Context ctx, ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        try {
            ContextHolder.runWith(ctx, () -> {
                // Mirror the scope identity into SLF4J's MDC: the Context lives in a
                // ScopedValue the logging system cannot see, so this is what puts
                // traceId / tenantId / userId on every log line (and on anything
                // MDC-aware, e.g. structured log encoders and Sentry events).
                MDC.put(MdcKeys.TRACE_ID, ctx.getTraceId());
                if (ctx.getTenantId() != null) {
                    MDC.put(MdcKeys.TENANT_ID, String.valueOf(ctx.getTenantId()));
                }
                if (ctx.getUserId() != null) {
                    MDC.put(MdcKeys.USER_ID, String.valueOf(ctx.getUserId()));
                }
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new RuntimeException(e);
                } finally {
                    MDC.remove(MdcKeys.TRACE_ID);
                    MDC.remove(MdcKeys.TENANT_ID);
                    MDC.remove(MdcKeys.USER_ID);
                }
            });
        } catch (RuntimeException re) {
            // unwrap checked exceptions to preserve servlet container semantics
            Throwable cause = re.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof ServletException se) throw se;
            throw re;
        }
    }

    /**
     * Set response as a JSON response body that contains redirection information to login,
     * enabling client custom redirection.
     *
     * @param response the HTTP response
     */
    private void userNotFound(HttpServletResponse response, UserNotFoundException e) {
        try {
            // Same code and message as ever, so a client that only knows "410 → log in again" keeps
            // working; errorCode tells a newer client WHICH of the two it is — the session is gone
            // (SESSION_EXPIRED), or only this organisation is (ORG_UNAVAILABLE).
            ApiResponseErrorDetails<Void> redirectResponse =
                    ApiResponseErrorDetails.exception(ResponseCode.USER_NOT_FOUND, e.getMessage())
                            .withErrorCode(e.getErrorCode(), e.getErrorDetails());
            redirectResponse.setMessage(e.getMessage());
            String jsonResponse = JsonUtils.objectToString(redirectResponse);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(jsonResponse);
        } catch (IOException io) {
            log.error("User not found", io);
        }
    }

    /**
     * Send error response as JSON.
     */
    private void responseError(HttpServletResponse response, ResponseCode code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponseErrorDetails<Void> apiResponse = ApiResponseErrorDetails.exception(code, message);
        response.getWriter().write(JsonUtils.objectToString(apiResponse));
    }
}
