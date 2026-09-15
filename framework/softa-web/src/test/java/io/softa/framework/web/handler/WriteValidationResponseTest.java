package io.softa.framework.web.handler;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.orm.service.validation.WriteValidationException;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.response.ApiResponseErrorDetails;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A refused write reaches the client as a 400 whose body names each rejected field — the one
 * addition the frontend needs to put a message on a field instead of a toast. Everything else about
 * the body (code, joined error, traceId) is what every other rejected input already gets.
 */
class WriteValidationResponseTest {

    @Test
    void fieldErrorsRideAlongWithTheUsualErrorBody() {
        WebExceptionHandler handler = new WebExceptionHandler();
        RequestInfoHandler requestInfo = Mockito.mock(RequestInfoHandler.class);
        Mockito.when(requestInfo.getRequestInfo()).thenReturn("");
        ReflectionTestUtils.setField(handler, "messageHandler", requestInfo);

        WriteValidationException e = new WriteValidationException(List.of(
                new WriteValidationException.FieldError(0, "postalCode", "Postal Code is required"),
                new WriteValidationException.FieldError(0, "postalCode", "second message for the same field is dropped"),
                new WriteValidationException.FieldError(0, "name", "Name is required")));

        ResponseEntity<ApiResponse<Void>> response = handler.handleException(e);
        ApiResponseErrorDetails<Void> body = (ApiResponseErrorDetails<Void>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(ResponseCode.BAD_REQUEST.getCode());
        assertThat(body.getError()).isEqualTo("postalCode: Postal Code is required; postalCode: second message for the same field is dropped; name: Name is required");
        assertThat(body.getFieldErrors()).containsExactly(
                java.util.Map.entry("postalCode", "Postal Code is required"),
                java.util.Map.entry("name", "Name is required"));
    }

    @Test
    void anOrdinaryRejectionCarriesNoFieldErrorsMember() {
        ApiResponseErrorDetails<Void> body = ApiResponseErrorDetails.exception(ResponseCode.BAD_REQUEST, "nope");
        assertThat(body.getFieldErrors()).isNull();
        assertThat(ApiResponseErrorDetails.exception(ResponseCode.BAD_REQUEST, "nope", java.util.Map.of()).getFieldErrors()).isNull();
    }
}
