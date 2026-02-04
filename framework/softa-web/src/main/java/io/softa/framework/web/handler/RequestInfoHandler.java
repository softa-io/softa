package io.softa.framework.web.handler;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.web.aspect.ApiExceptionAspect;
import io.softa.framework.web.wrapper.CachedBodyHttpServletRequest;

/**
 * Request info handler
 */
@Component
public class RequestInfoHandler {

    @Autowired
    private HttpServletRequest request;

    /**
     * Get the current request info, including the URI, request parameters, request body, and traceId.
     *
     * @return A concatenated string of request info.
     */
    public String getRequestInfo() {
        StringBuilder builder = new StringBuilder(" Request: ");
        if (request != null) {
            builder.append(request.getRequestURL().toString());
            String queryString = request.getQueryString();
            if (StringUtils.isNotBlank(queryString)) {
                builder.append("?").append(queryString);
            }
            appendUserAndTraceId(builder);
            appendClientIp(builder);
            appendRequestParams(builder);
            appendRequestBody(builder);
        } else {
            appendUserAndTraceId(builder);
        }
        return builder.toString();
    }

    /**
     * Append user info and TraceID to the builder.
     */
    private void appendUserAndTraceId(StringBuilder builder) {
        if (ContextHolder.existContext()) {
            builder.append(" ; User: ").append(ContextHolder.getContext().getName());
            builder.append(" ; TraceID: ").append(ContextHolder.getContext().getTraceId());
        } else {
            String traceId = request.getHeader(BaseConstant.X_B3_TRACEID);
            if (StringUtils.isNotBlank(traceId)) {
                builder.append(" ; TraceID: ").append(traceId);
            }
        }
    }

    /**
     * Append client IP to the builder, considering proxy headers.
     */
    private void appendClientIp(StringBuilder builder) {
        String clientIp = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(clientIp)) {
            builder.append(" ; ClientIP: ").append(clientIp);
        } else {
            clientIp = request.getHeader("X-Real-IP");
            if (StringUtils.isNotBlank(clientIp)) {
                builder.append(" ; ClientRealIP: ").append(clientIp);
            }
        }
    }

    /**
     * Appends request parameters to the builder.
     */
    private void appendRequestParams(StringBuilder builder) {
        String receivedParams = (String) request.getAttribute(ApiExceptionAspect.REQUEST_PARAMS);
        if (StringUtils.isNotBlank(receivedParams)) {
            builder.append("\n").append(receivedParams);
        }
    }

    private void appendRequestBody(StringBuilder builder) {
        if (SystemConfig.env.isEnableLogRequestBody()
                && request instanceof CachedBodyHttpServletRequest cachedBodyRequest) {
            String requestBody = cachedBodyRequest.getCachedBodyAsString();
            if (StringUtils.isNotBlank(requestBody)) {
                builder.append("\n").append(requestBody);
            }
        } else {
            builder.append(" ; RequestBody: [hidden]");
        }
    }
}