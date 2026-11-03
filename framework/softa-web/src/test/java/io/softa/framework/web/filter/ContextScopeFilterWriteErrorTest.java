package io.softa.framework.web.filter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.web.response.ApiResponseErrorDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Another filter refusing a request answers in the same shape as this one: HTTP 200, JSON body,
 * error code and details carried through.
 */
class ContextScopeFilterWriteErrorTest {

    @Test
    void writeError_answersWithTheErrorInTheJsonBody() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        ContextScopeFilter.writeError(response, ApiResponseErrorDetails
                .exception(ResponseCode.BUSINESS_EXCEPTION, "Please update.")
                .withErrorCode("UPGRADE_REQUIRED", Map.of("platform", "ios")));

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setContentType("application/json");
        assertThat(body.toString())
                .contains("\"errorCode\":\"UPGRADE_REQUIRED\"")
                .contains("\"platform\":\"ios\"")
                .contains("Please update.");
    }
}
