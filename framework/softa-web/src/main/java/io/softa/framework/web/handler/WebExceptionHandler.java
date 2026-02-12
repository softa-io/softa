package io.softa.framework.web.handler;

import java.util.List;
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
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.exception.BaseException;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.i18n.I18n;
import io.softa.framework.web.response.ApiResponse;
import io.softa.framework.web.response.ApiResponseErrorDetails;

/**
 * Web request exception handler, which catch the API exception that requires specifying responseCode.
 * Error response body:
 * {
 * "code": responseCode,
 * "message": statusMessage as request Exception category,
 * "data": errorMessage for end users
 * }
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionHandler {

    @Autowired
    private RequestInfoHandler messageHandler;

    /**
     * Handling exception and logging errors.
     * errorMessage is joint/translated already, which would be put in response data.
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
        String clientExceptionMessage;
        if (ContextHolder.getContext().isDebug()) {
            clientExceptionMessage = e.toString();
        } else {
            clientExceptionMessage = e.getMessage();
        }
        return handler(ResponseCode.ERROR, e, clientExceptionMessage);
    }

    /**
     * Handle BaseException and its children exceptions.
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = BaseException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(BaseException e) {
        return handler(e.getResponseCode(), e);
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
        return handler(ResponseCode.BAD_REQUEST, e, "Failed to parse the request body!");
    }

    /**
     * Handle DataIntegrityViolationException
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(DataIntegrityViolationException e) {
        return handler(ResponseCode.BAD_SQL_STATEMENT, e);
    }

    /**
     * Validation Errors of API Parameters
     * Handle MethodArgumentNotValidException
     *
     * @param e Exception
     * @return All the validation error messages.
     */
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(MethodArgumentNotValidException e) {
        StringBuilder errorMessage = new StringBuilder("Validation Error: {}");
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors();
        for (FieldError fieldError : fieldErrors) {
            if (!errorMessage.isEmpty()) {
                errorMessage.append(" \n");
            }
            errorMessage.append(fieldError.getDefaultMessage());
        }
        return handler(ResponseCode.BAD_REQUEST, e, errorMessage.toString());
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
     * Handle ValidationException
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(BusinessException e) {
        ApiResponse<Void> response = ApiResponseErrorDetails.exception(e.getResponseCode(), e.getMessage());
        log.warn("BusinessException: {}", e.getMessage(), e);
        return new ResponseEntity<>(response, HttpStatus.OK);
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

    /**
     * Handle DuplicateKeyException
     *
     * @param e Exception
     * @return ResponseEntity
     */
    @ExceptionHandler(value = DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleException(DuplicateKeyException e) {
        String errorMessage = "Duplicate Key Error";
        if (e.getCause() instanceof Exception) {
            errorMessage = e.getCause().getMessage();
        }
        return handler(ResponseCode.BAD_REQUEST, e, errorMessage);
    }

}
