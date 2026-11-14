package io.softa.framework.web.handler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.exception.BaseException;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.service.validation.WriteValidationException;
import io.softa.framework.base.i18n.I18n;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.response.ApiResponseErrorDetails;

/**
 * Web request exception handler, which catch the API exception that requires specifying responseCode.
 * Error response body (see {@link ApiResponseErrorDetails}):
 * {
 * "code": responseCode,
 * "message": category label of the response code,
 * "data": optional error payload,
 * "error": user-facing error message (i18n-resolved),
 * "traceId": server-side trace ID for log correlation,
 * "errorCode": stable machine-readable reason, only when the exception carries one,
 * "errorDetails": values accompanying errorCode, only when present
 * }
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionHandler {

    @Autowired
    private RequestInfoHandler messageHandler;

    /**
     * Handling exception and logging errors.
     * errorMessage is joint/translated already, which would be put in the response `error` field.
     *
     * @param responseCode responseCode
     * @return ResponseEntity
     */
    private ResponseEntity<ApiResponse<Void>> wrapResponse(ResponseCode responseCode, String exceptionMessage) {
        ApiResponse<Void> response = ApiResponseErrorDetails.exception(responseCode, exceptionMessage);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Regard e.getMessage() as errorMessage if no errorMessage is specified to end user during handling XXXExceptions
     * errorMessage is translated already in BaseException or corresponding handler.
     *
     * @param responseCode ResponseCode
     * @param e            Exception
     * @return ResponseEntity
     */
    private ResponseEntity<ApiResponse<Void>> handler(ResponseCode responseCode, Throwable e) {
        String exceptionMessage = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
        return handler(responseCode, e, exceptionMessage);
    }

    /**
     * Support i18n of Java exception messages and third-party exception messages.
     * Logs exception messages, including API request info.
     *
     * @param responseCode     the response code associated with the exception
     * @param e                the exception that was thrown
     * @param exceptionMessage the custom message for the exception
     * @return a ResponseEntity wrapping the ApiResponse with the specified message
     */
    private ResponseEntity<ApiResponse<Void>> handler(ResponseCode responseCode, Throwable e, String exceptionMessage) {
        if (!(e instanceof BaseException)) {
            // If the exception is not an instance of BaseException, translate the exception message.
            // Support i18n of Java exception messages and third-party exception messages.
            exceptionMessage = I18n.get(exceptionMessage);
        }
        String logMessage = exceptionMessage + messageHandler.getRequestInfo();
        if (e instanceof BaseException baseE && baseE.getLogLevel().equals(Level.WARN)) {
            // If the logLevel of Exception is WARN, log as a warning without triggering error alerts.
            log.warn(logMessage, e);
        } else {
            log.error(logMessage, e);
        }
        if (e instanceof BaseException baseE && baseE.getErrorCode() != null) {
            ApiResponseErrorDetails<Void> response = ApiResponseErrorDetails.exception(responseCode, exceptionMessage)
                    .withErrorCode(baseE.getErrorCode(), baseE.getErrorDetails());
            return new ResponseEntity<>(response, HttpStatus.OK);
        }
        return wrapResponse(responseCode, exceptionMessage);
    }

    /**
     * Handle normal Exception
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = Throwable.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        // The full detail always goes to the error log; the client payload
        // carries it only in debug mode — raw messages of unexpected
        // exceptions can leak internals (SQL fragments, file paths, class
        // names). Non-debug clients get the generic category message plus the
        // traceId to hand to support.
        log.error(e + messageHandler.getRequestInfo(), e);
        String clientExceptionMessage = ContextHolder.getContext().isDebug() ? e.toString() : null;
        return wrapResponse(ResponseCode.ERROR, clientExceptionMessage);
    }

    /**
     * Handle BaseException and its children exceptions (BusinessException
     * included — its WARN log level is honored by the level-aware logging in
     * {@link #handler(ResponseCode, Throwable, String)}, and the message is
     * already i18n-resolved at construction).
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = BaseException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(BaseException e) {
        return handler(e.getResponseCode(), e);
    }

    /**
     * A refusal that names fields carries them as {@code fieldErrors}: those sentences are written for
     * the user, where the message of any other refusal is detail for the server log. Logged and coded
     * like every other permission refusal.
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = PermissionException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(PermissionException e) {
        ResponseEntity<ApiResponse<Void>> response = handler(e.getResponseCode(), e);
        if (e.getFieldErrors() == null || e.getFieldErrors().isEmpty()) {
            return response;
        }
        ApiResponseErrorDetails<Void> body = (ApiResponseErrorDetails<Void>) response.getBody();
        body.setFieldErrors(e.getFieldErrors());
        return response;
    }

    /**
     * Handle NoHandlerFoundException
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(NoHandlerFoundException e) {
        return handler(ResponseCode.REQUEST_NOT_FOUND, e);
    }

    /**
     * Handle HttpRequestMethodNotSupportedException
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(HttpRequestMethodNotSupportedException e) {
        return handler(ResponseCode.HTTP_BAD_METHOD, e);
    }

    @ExceptionHandler(value = MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(MaxUploadSizeExceededException e) {
        String message = "Maximum upload size exceeded";
        return handler(ResponseCode.BAD_REQUEST, e, message);
    }

    /**
     * A write refused by field constraints or the {@code ModelWriteValidator} chain: a 400 like any
     * rejected input, but the body also carries {@code fieldErrors} so a form can place each sentence
     * on its field. Declared before the {@code BaseException} handler resolves it — Spring picks the
     * most specific exception type, so this one wins for the subclass.
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = WriteValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(WriteValidationException e) {
        String exceptionMessage = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
        log.warn(exceptionMessage + messageHandler.getRequestInfo(), e);
        ApiResponse<Void> response = ApiResponseErrorDetails.exception(e.getResponseCode(), exceptionMessage, e.fieldErrors());
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * Handle IllegalArgumentException
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(IllegalArgumentException e) {
        return handler(ResponseCode.BAD_REQUEST, e);
    }

    /**
     * Handle HttpMessageNotReadableException
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(HttpMessageNotReadableException e) {
        Optional<Map.Entry<String, String>> refused = unreadableValue(e);
        if (refused.isEmpty()) {
            return handler(ResponseCode.BAD_REQUEST, e, "Failed to parse the request body!");
        }
        ResponseEntity<ApiResponse<Void>> response = handler(ResponseCode.BAD_REQUEST, e,
                refused.get().getKey() + ": " + refused.get().getValue());
        if (response.getBody() instanceof ApiResponseErrorDetails<Void> details) {
            details.setFieldErrors(Map.of(refused.get().getKey(), refused.get().getValue()));
        }
        return response;
    }

    /**
     * The body value that could not be read into its type, as path → sentence: an end date of
     * {@code 2026-13-45} reads {@code authorizations[0].endDate → `2026-13-45` is not a valid date
     * (yyyy-MM-dd).} Empty when the body is not well-formed at all, which has no field to name.
     */
    static Optional<Map.Entry<String, String>> unreadableValue(HttpMessageNotReadableException e) {
        if (!(e.getCause() instanceof MismatchedInputException mismatch)) {
            return Optional.empty();
        }
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : mismatch.getPath()) {
            if (reference.getPropertyName() != null) {
                path.append(path.isEmpty() ? "" : ".").append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        if (path.isEmpty()) {
            return Optional.empty();
        }
        String expected = describeType(mismatch.getTargetType());
        String sentence = mismatch instanceof InvalidFormatException invalid && invalid.getValue() != null
                ? "`" + invalid.getValue() + "` is not a valid " + expected + "."
                : "Expected a " + expected + ".";
        return Optional.of(Map.entry(path.toString(), sentence));
    }

    /** What a value of this type is, in the words of whoever sent it. */
    private static String describeType(Class<?> type) {
        if (type == null) {
            return "value";
        }
        if (type == LocalDate.class) {
            return "date (yyyy-MM-dd)";
        }
        if (type == LocalDateTime.class) {
            return "date and time (yyyy-MM-dd HH:mm:ss)";
        }
        if (type == LocalTime.class) {
            return "time (HH:mm:ss)";
        }
        if (Number.class.isAssignableFrom(type)
                || (type.isPrimitive() && type != boolean.class && type != char.class)) {
            return "number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean (true or false)";
        }
        if (type.isEnum()) {
            return "option";
        }
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return "list";
        }
        if (type == String.class) {
            return "text";
        }
        return "value";
    }

    /**
     * Handle MissingServletRequestParameterException: a required query parameter left out — the
     * body sent instead of {@code ?profileId=}, say. Before this it fell to the catch-all and came back
     * as a 500 system exception.
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(MissingServletRequestParameterException e) {
        return handler(ResponseCode.BAD_REQUEST, e,
                "The request parameter `" + e.getParameterName() + "` is required.");
    }

    /**
     * Handle MethodArgumentTypeMismatchException: a query parameter or path variable that does not
     * convert to its type ({@code ?profileId=abc}).
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(MethodArgumentTypeMismatchException e) {
        return handler(ResponseCode.BAD_REQUEST, e,
                e.getName() + ": `" + e.getValue() + "` is not a valid " + describeType(e.getRequiredType()) + ".");
    }

    /**
     * Handle DataIntegrityViolationException
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(DataIntegrityViolationException e) {
        // One handler for the whole family: DuplicateKeyException is a subclass, and a unique
        // violation can also surface as a plain DataIntegrityViolationException depending on the
        // dialect/translator. Try the friendly per-index message first; otherwise fall back.
        Optional<DuplicateKeyMessageResolver.DuplicateKeyMessage> friendly = DuplicateKeyMessageResolver.resolve(e);
        if (friendly.isPresent()) {
            log.info("Unique constraint violation [{}]{}", friendly.get().logDetail(), messageHandler.getRequestInfo());
            return wrapResponse(ResponseCode.BAD_REQUEST, friendly.get().userMessage());
        }
        // Unmapped: a duplicate is a client conflict; other integrity violations are a bad statement.
        // Generic, value-free fallbacks — never the raw driver text.
        boolean duplicate = e instanceof DuplicateKeyException;
        String fallback = duplicate
                ? "A record with a duplicate value already exists."
                : "The operation violates a data integrity constraint.";
        return handler(duplicate ? ResponseCode.BAD_REQUEST : ResponseCode.BAD_SQL_STATEMENT, e, fallback);
    }

    /**
     * Validation Errors of API Parameters
     * Handle MethodArgumentNotValidException
     * Each message is written as {@code field: message}, the form a refused write uses too:
     * a default message such as "must not be blank" does not say which field it is about.
     * The body also carries {@code fieldErrors}, field → message, as a refused write does, so a form can
     * put each sentence on its control instead of showing the joined text; when a field has several
     * messages the first is kept.
     *
     * @param e Exception
     * @return All the validation error messages.
     */
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(MethodArgumentNotValidException e) {
        StringBuilder errorMessage = new StringBuilder();
        Map<String, String> byField = new LinkedHashMap<>();
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors();
        for (FieldError fieldError : fieldErrors) {
            if (!errorMessage.isEmpty()) {
                errorMessage.append("; ");
            }
            errorMessage.append(fieldError.getField()).append(": ").append(fieldError.getDefaultMessage());
            byField.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ResponseEntity<ApiResponse<Void>> response = handler(ResponseCode.BAD_REQUEST, e, "Validation Error: " + errorMessage);
        if (response.getBody() instanceof ApiResponseErrorDetails<Void> details && !byField.isEmpty()) {
            details.setFieldErrors(byField);
        }
        return response;
    }

    /**
     * Handle UnsatisfiedServletRequestParameterException
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = UnsatisfiedServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(UnsatisfiedServletRequestParameterException e) {
        return handler(ResponseCode.BAD_REQUEST, e);
    }

    /**
     * Handle NoSuchMethodError
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = NoSuchMethodError.class)
    public ResponseEntity<ApiResponse<Void>> handleException(NoSuchMethodError e) {
        return handler(ResponseCode.BAD_REQUEST, e);
    }

    /**
     * Handle ConstraintViolationException
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(ConstraintViolationException e) {
        return handler(ResponseCode.BAD_REQUEST, e);
    }

    /**
     * Handle ValidationException
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = ValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(ValidationException e) {
        return handler(ResponseCode.ERROR, e);
    }

    /**
     * Handle ClientAbortException.
     *
     * @param e Exception
     */
    @ExceptionHandler(value = ClientAbortException.class)
    public void handleException(ClientAbortException e) {
        log.warn("ClientAbortException: {}", e.getMessage());
    }

    /**
     * Handle BadSqlGrammarException
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = BadSqlGrammarException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(BadSqlGrammarException e) {
        String errorMessage = "SQL Syntax Error";
        return handler(ResponseCode.ERROR, e, errorMessage);
    }

}
