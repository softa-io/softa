package io.softa.starter.flow.controller;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.response.ApiResponseErrorDetails;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.api.FlowCompileException;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

/**
 * Exception handler for flow-starter-specific exceptions.
 *
 * <p>Lives in the {@code flow-starter} module so it can reference
 * {@link FlowCompileException} without introducing a downward dependency in
 * {@code softa-web}'s {@code WebExceptionHandler}.</p>
 *
 * <p>Follows the framework convention of returning HTTP 200 with a
 * {@link ResponseCode} business code in the response body.</p>
 */
@Slf4j
@RestControllerAdvice
public class FlowExceptionHandler {

    @ExceptionHandler(FlowCompileException.class)
    public ResponseEntity<ApiResponse<List<CompileDiagnostic>>> handleFlowCompileException(FlowCompileException ex) {
        ApiResponse<List<CompileDiagnostic>> response = new ApiResponse<>(
                ResponseCode.BAD_REQUEST.getCode(),
                "Flow compilation failed: " + ex.getMessage(),
                ex.getDiagnostics());
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(FlowActionValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleFlowActionValidationException(FlowActionValidationException ex) {
        log.warn("FlowActionValidationException: {}", ex.getMessage());
        return ResponseEntity.ok(ApiResponseErrorDetails.exception(ResponseCode.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(FlowAuthorizationException.class)
    public ResponseEntity<ApiResponse<Void>> handleFlowAuthorizationException(FlowAuthorizationException ex) {
        log.warn("FlowAuthorizationException: {}", ex.getMessage());
        return ResponseEntity.ok(ApiResponseErrorDetails.exception(ResponseCode.PERMISSION_DENIED, ex.getMessage()));
    }

    @ExceptionHandler(FlowRuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleFlowRuntimeException(FlowRuntimeException ex) {
        log.error("FlowRuntimeException: {}", ex.getMessage(), ex);
        return ResponseEntity.ok(ApiResponseErrorDetails.exception(ResponseCode.ERROR, ex.getMessage()));
    }
}
