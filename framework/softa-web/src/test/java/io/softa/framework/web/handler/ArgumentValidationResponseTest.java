package io.softa.framework.web.handler;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.response.ApiResponseErrorDetails;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request body that fails its constraints comes back as a 400 whose error names the field of every message,
 * in the {@code field: message} form a refused write uses, with {@code fieldErrors} naming each field as well.
 */
class ArgumentValidationResponseTest {

    /** Every message names its field, whether it is the validator's default or one written for it. */
    @Test
    void everyMessageNamesItsField() throws NoSuchMethodException {
        ApiResponseErrorDetails<Void> body = respond(
                new FieldError("order", "code", "must not be blank"),
                new FieldError("order", "amount", "The amount is required."));
        assertThat(body.getCode()).isEqualTo(ResponseCode.BAD_REQUEST.getCode());
        assertThat(body.getError()).isEqualTo("Validation Error: code: must not be blank; amount: The amount is required.");
    }

    /** A single message follows the prefix directly, with no placeholder or line break before it. */
    @Test
    void singleMessageFollowsThePrefix() throws NoSuchMethodException {
        ApiResponseErrorDetails<Void> body = respond(new FieldError("order", "code", "must not be blank"));
        assertThat(body.getError()).isEqualTo("Validation Error: code: must not be blank");
    }

    /** The body also names each rejected field, so a form can put the sentence on its control. */
    @Test
    void fieldErrorsNameEachRejectedField() throws NoSuchMethodException {
        ApiResponseErrorDetails<Void> body = respond(
                new FieldError("order", "startDate", "Start date is required"),
                new FieldError("order", "amount", "The amount is required."));
        assertThat(body.getFieldErrors()).containsExactly(
                Map.entry("startDate", "Start date is required"),
                Map.entry("amount", "The amount is required."));
    }

    /** A field rejected twice keeps its first message in fieldErrors; the joined error still has both. */
    @Test
    void aFieldRejectedTwiceKeepsItsFirstMessage() throws NoSuchMethodException {
        ApiResponseErrorDetails<Void> body = respond(
                new FieldError("order", "code", "must not be blank"),
                new FieldError("order", "code", "size must be between 1 and 32"));
        assertThat(body.getFieldErrors()).containsExactly(Map.entry("code", "must not be blank"));
        assertThat(body.getError()).isEqualTo("Validation Error: code: must not be blank; code: size must be between 1 and 32");
    }

    /** Runs the handler on a request body rejected with these field errors. */
    private static ApiResponseErrorDetails<Void> respond(FieldError... fieldErrors) throws NoSuchMethodException {
        WebExceptionHandler handler = new WebExceptionHandler();
        RequestInfoHandler requestInfo = Mockito.mock(RequestInfoHandler.class);
        Mockito.when(requestInfo.getRequestInfo()).thenReturn("");
        ReflectionTestUtils.setField(handler, "messageHandler", requestInfo);

        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "order");
        for (FieldError fieldError : fieldErrors) {
            bindingResult.addError(fieldError);
        }
        MethodParameter parameter = new MethodParameter(ArgumentValidationResponseTest.class.getDeclaredMethod("create", Object.class), 0);
        ResponseEntity<ApiResponse<Void>> response = handler.handleException(new MethodArgumentNotValidException(parameter, bindingResult));
        return (ApiResponseErrorDetails<Void>) response.getBody();
    }

    /** Stands in for the endpoint whose request body failed validation. */
    @SuppressWarnings("unused")
    private void create(Object order) {
    }
}
