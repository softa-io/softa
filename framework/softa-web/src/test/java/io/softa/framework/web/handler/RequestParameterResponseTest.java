package io.softa.framework.web.handler;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.response.ApiResponseErrorDetails;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request parameter or upload part that is missing, or a parameter of the wrong type, is the caller's
 * mistake: a 400 naming it, not the 500 "System exception" each used to fall through to.
 */
class RequestParameterResponseTest {

    @Test
    void aMissingParameterIsABadRequestThatNamesIt() {
        ApiResponseErrorDetails<Void> body = body(handler().handleException(
                new MissingServletRequestParameterException("id", "Long")));

        assertThat(body.getCode()).isEqualTo(ResponseCode.BAD_REQUEST.getCode());
        assertThat(body.getError()).contains("'id'");
    }

    /** Unmet parameter conditions were a 400 before the handler took their parent class, and stay one. */
    @Test
    void unmetParameterConditionsStayABadRequest() {
        ApiResponseErrorDetails<Void> body = body(handler().handleException(
                new UnsatisfiedServletRequestParameterException(new String[] {"mode=copy"}, Map.of())));

        assertThat(body.getCode()).isEqualTo(ResponseCode.BAD_REQUEST.getCode());
        assertThat(body.getError()).contains("mode=copy");
    }

    /** A path variable the URI template does not declare is a mistake in the code: still a system error. */
    @Test
    void aPathVariableMissingFromTheTemplateStaysASystemError() throws NoSuchMethodException {
        ApiResponseErrorDetails<Void> body = ContextHolder.callWith(new Context(), () -> body(handler().handleException(
                new MissingPathVariableException("modelName", modelNameParameter()))));

        assertThat(body.getCode()).isEqualTo(ResponseCode.ERROR.getCode());
    }

    /** One present in the path that converted to nothing is the caller's: a 400. */
    @Test
    void aPathVariableEmptyAfterConversionIsABadRequest() throws NoSuchMethodException {
        ApiResponseErrorDetails<Void> body = body(handler().handleException(
                new MissingPathVariableException("modelName", modelNameParameter(), true)));

        assertThat(body.getCode()).isEqualTo(ResponseCode.BAD_REQUEST.getCode());
        assertThat(body.getError()).contains("modelName");
    }

    /** An upload without its file: the missing part is the caller's, a 400 that names it. */
    @Test
    void aMissingFilePartIsABadRequestThatNamesIt() {
        ApiResponseErrorDetails<Void> body = body(handler().handleException(
                new MissingServletRequestPartException("file")));

        assertThat(body.getCode()).isEqualTo(ResponseCode.BAD_REQUEST.getCode());
        assertThat(body.getError()).contains("'file'");
    }

    @Test
    void aParameterOfTheWrongTypeIsABadRequestThatNamesItAndTheValue() throws NoSuchMethodException {
        MethodParameter parameter = new MethodParameter(
                RequestParameterResponseTest.class.getDeclaredMethod("deleteById", Long.class), 0);
        ApiResponseErrorDetails<Void> body = body(handler().handleException(
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", parameter, null)));

        assertThat(body.getCode()).isEqualTo(ResponseCode.BAD_REQUEST.getCode());
        assertThat(body.getError()).isEqualTo("Parameter 'id' must be a Long, got 'abc'.");
    }

    private static WebExceptionHandler handler() {
        WebExceptionHandler handler = new WebExceptionHandler();
        RequestInfoHandler requestInfo = Mockito.mock(RequestInfoHandler.class);
        Mockito.when(requestInfo.getRequestInfo()).thenReturn("");
        ReflectionTestUtils.setField(handler, "messageHandler", requestInfo);
        return handler;
    }

    @SuppressWarnings("unchecked")
    private static ApiResponseErrorDetails<Void> body(ResponseEntity<ApiResponse<Void>> response) {
        return (ApiResponseErrorDetails<Void>) response.getBody();
    }

    private static MethodParameter modelNameParameter() throws NoSuchMethodException {
        return new MethodParameter(RequestParameterResponseTest.class.getDeclaredMethod("search", String.class), 0);
    }

    /** Stands in for the endpoint whose parameter failed to bind. */
    @SuppressWarnings("unused")
    private void deleteById(Long id) {
    }

    /** Stands in for an endpoint that binds a path variable. */
    @SuppressWarnings("unused")
    private void search(String modelName) {
    }
}
