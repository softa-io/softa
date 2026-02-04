package io.softa.framework.web.filter;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.web.wrapper.CachedBodyHttpServletRequest;

/**
 * Cache request body for exception handler, only for JSON request and enableLogRequestBody is true
 */
@Component
public class RequestBodyCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        // Only cache request body for JSON request when enableLogRequestBody is true
        if (SystemConfig.env.isEnableLogRequestBody()
                && request.getContentType() != null
                && request.getContentType().contains(MediaType.APPLICATION_JSON_VALUE)) {
            CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);
            filterChain.doFilter(wrappedRequest, response);
        } else {
            filterChain.doFilter(request, response);
        }
    }
}