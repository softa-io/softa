package io.softa.framework.base.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.slf4j.event.Level;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.i18n.I18n;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BaseException extends RuntimeException {

    private String message;
    private Level logLevel = Level.ERROR;
    private ResponseCode responseCode = ResponseCode.ERROR;

    /**
     * Stable, machine-readable reason, such as {@code SESSION_EXPIRED} or {@code PASSWORD_LOCKED}.
     * The numeric response code says which family a failure belongs to; this says which failure it
     * is, so a client can show its own copy rather than parse the server's message. Null when the
     * failure has no code of its own.
     */
    private String errorCode;

    /** Values a client needs alongside {@link #errorCode}, such as {@code remainingAttempts}. */
    private Map<String, Object> errorDetails;

    /**
     * Accept variable parameter exception message.
     * @param message: Exception message, accept placeholder message, support i18n
     * @param args:    Variable parameters, support the last parameter as Throwable object
     */
    public BaseException(String message, Object... args) {
        super(message);
        if (args == null || args.length == 0) {
            message = I18n.get(message);
        } else if (args[args.length - 1] instanceof Throwable) {
            // Determine whether the last parameter is a Throwable object,
            // If so, use it as the cause and output it to the log with the current exception object
            this.initCause((Throwable) args[args.length - 1]);
            // Remove the last parameter to avoid translation errors
            if (args.length == 1) {
                message = I18n.get(message);
            } else {
                Object[] newArgs = new Object[args.length - 1];
                System.arraycopy(args, 0, newArgs, 0, args.length - 1);
                message = I18n.get(message, newArgs);
            }
        } else {
            message = I18n.get(message, args);
        }
        this.message = message;
    }

    /**
     * Attach a stable error code; returns this exception so it can be thrown in one expression.
     */
    public BaseException withErrorCode(String errorCode) {
        this.errorCode = errorCode;
        return this;
    }

    /**
     * Attach one value a client needs alongside the error code.
     */
    public BaseException withErrorDetail(String key, Object value) {
        if (this.errorDetails == null) {
            this.errorDetails = new LinkedHashMap<>();
        }
        this.errorDetails.put(key, value);
        return this;
    }
}
