package io.softa.framework.web.handler;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.response.ApiResponseErrorDetails;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A permission refusal reaches the client as a 403 either way. Only one that names fields carries
 * {@code fieldErrors}: that is how a client tells a sentence written for the user from a refusal whose
 * message is detail for the server log, and shows the first instead of a generic denial.
 */
class PermissionResponseTest {

    private WebExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WebExceptionHandler();
        RequestInfoHandler requestInfo = Mockito.mock(RequestInfoHandler.class);
        Mockito.when(requestInfo.getRequestInfo()).thenReturn("");
        ReflectionTestUtils.setField(handler, "messageHandler", requestInfo);
    }

    @Test
    void aRefusedFieldRidesAlongAsFieldErrors() {
        PermissionException e = PermissionException.forField("ipaBasic",
                "You don't have permission to edit IPA fields for this employee.");

        ApiResponseErrorDetails<Void> body = bodyOf(handler.handleException(e));
        assertThat(body.getCode()).isEqualTo(ResponseCode.PERMISSION_DENIED.getCode());
        assertThat(body.getError()).isEqualTo("You don't have permission to edit IPA fields for this employee.");
        assertThat(body.getFieldErrors())
                .isEqualTo(Map.of("ipaBasic", "You don't have permission to edit IPA fields for this employee."));
    }

    @Test
    void anyOtherRefusalCarriesNoFieldErrorsMember() {
        ApiResponseErrorDetails<Void> body = bodyOf(handler.handleException(
                new PermissionException("Endpoint not registered: POST /X/y")));
        assertThat(body.getCode()).isEqualTo(ResponseCode.PERMISSION_DENIED.getCode());
        assertThat(body.getFieldErrors()).isNull();
    }

    private static ApiResponseErrorDetails<Void> bodyOf(ResponseEntity<ApiResponse<Void>> response) {
        assertThat(response.getBody()).isInstanceOf(ApiResponseErrorDetails.class);
        return (ApiResponseErrorDetails<Void>) response.getBody();
    }
}
