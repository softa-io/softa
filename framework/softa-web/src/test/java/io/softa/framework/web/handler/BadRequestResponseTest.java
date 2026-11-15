package io.softa.framework.web.handler;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.MissingServletRequestParameterException;
import tools.jackson.databind.json.JsonMapper;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.response.ApiResponseErrorDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A request the framework cannot bind is the caller's mistake, answered with a 400 that names the
 * parameter or body field at fault — not the bare "Failed to parse the request body!", and not a 500
 * system exception for a required query parameter left out.
 */
class BadRequestResponseTest {

    record Grant(Long tenantId, LocalDate endDate) {
    }

    record Form(String username, List<Grant> authorizations) {
    }

    record Patch(List<Grant> create) {
    }

    record PatchForm(String username, Patch authorizations) {
    }

    private WebExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WebExceptionHandler();
        RequestInfoHandler requestInfo = Mockito.mock(RequestInfoHandler.class);
        Mockito.when(requestInfo.getRequestInfo()).thenReturn("");
        ReflectionTestUtils.setField(handler, "messageHandler", requestInfo);
    }

    @Test
    void anInvalidDateInTheBodyNamesItsFieldAndTheFormat() {
        ApiResponseErrorDetails<Void> body = bodyOf(handler.handleException(unreadable(
                "{\"username\":\"a\",\"authorizations\":[{\"tenantId\":1,\"endDate\":\"2026-13-45\"}]}")));

        assertThat(body.getCode()).isEqualTo(ResponseCode.BAD_REQUEST.getCode());
        assertThat(body.getError())
                .isEqualTo("authorizations[0].endDate: `2026-13-45` is not a valid date (yyyy-MM-dd).");
        assertThat(body.getFieldErrors())
                .isEqualTo(Map.of("authorizations[0].endDate", "`2026-13-45` is not a valid date (yyyy-MM-dd)."));
    }

    @Test
    void aListWhereAnObjectGoesIsToldItWantsAnObject() {
        Throwable cause = catchThrowable(() -> JsonMapper.builder().build()
                .readValue("{\"username\":\"a\",\"authorizations\":[{\"tenantId\":1}]}", PatchForm.class));
        ApiResponseErrorDetails<Void> body = bodyOf(handler.handleException(
                new HttpMessageNotReadableException("JSON parse error", cause, Mockito.mock(HttpInputMessage.class))));

        assertThat(body.getError()).isEqualTo("authorizations: Expected an object.");
    }

    @Test
    void aBodyThatIsNotJsonAtAllKeepsTheGeneralMessage() {
        ApiResponseErrorDetails<Void> body = bodyOf(handler.handleException(unreadable("{not json")));

        assertThat(body.getCode()).isEqualTo(ResponseCode.BAD_REQUEST.getCode());
        assertThat(body.getError()).isEqualTo("Failed to parse the request body!");
    }

    @Test
    void aMissingQueryParameterIsABadRequestNotASystemException() {
        ApiResponseErrorDetails<Void> body = bodyOf(handler.handleException(
                new MissingServletRequestParameterException("profileId", "Long")));

        assertThat(body.getCode()).isEqualTo(ResponseCode.BAD_REQUEST.getCode());
        assertThat(body.getError()).isEqualTo("The request parameter `profileId` is required.");
    }

    private static HttpMessageNotReadableException unreadable(String json) {
        Throwable cause = catchThrowable(() -> JsonMapper.builder().build().readValue(json, Form.class));
        assertThat(cause).isNotNull();
        return new HttpMessageNotReadableException("JSON parse error", cause, Mockito.mock(HttpInputMessage.class));
    }

    @SuppressWarnings("unchecked")
    private static ApiResponseErrorDetails<Void> bodyOf(ResponseEntity<ApiResponse<Void>> response) {
        assertThat(response.getBody()).isInstanceOf(ApiResponseErrorDetails.class);
        return (ApiResponseErrorDetails<Void>) response.getBody();
    }
}
