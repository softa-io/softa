package io.softa.framework.web.filter;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.exception.UserNotFoundException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.web.enums.IdentifyType;
import io.softa.framework.web.filter.context.ContextBuilder;
import io.softa.framework.web.filter.context.IdentityResolver;
import io.softa.framework.web.response.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

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
                log.info("User context resolution failed: {}", e.getMessage());
                this.userNotFound(httpRes, e.getMessage());
            }
        } else if (IdentifyType.ANONYMOUS.equals(identifyRequired)) {
            Context anonymousContext = contextBuilder.buildAnonymousContext(httpReq);
            runInScope(anonymousContext, request, response, chain);
        } else {
            // IdentifyType.NONE, skip context binding
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
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new RuntimeException(e);
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
    private void userNotFound(HttpServletResponse response, String message) {
        try {
            ApiResponse<Void> redirectResponse = ApiResponse.error(ResponseCode.USER_NOT_FOUND, message);
            String jsonResponse = JsonUtils.objectToString(redirectResponse);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(jsonResponse);
        } catch (IOException e) {
            log.error("User not found", e);
        }
    }
}
