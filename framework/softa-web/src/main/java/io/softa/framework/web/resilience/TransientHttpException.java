package io.softa.framework.web.resilience;

import java.io.Serial;
import java.net.URI;
import lombok.Getter;

/**
 * Raised inside {@link ResilienceInterceptor} when an HTTP response status is judged
 * transient (5xx, 408, 429 by default). The exception is the vehicle that lets
 * Resilience4j's {@code Retry} and {@code CircuitBreaker} count the outcome as a
 * failure — both are exception-driven, not status-code-driven.
 * <p>
 * Callers unwrap this at the business layer only if they care about distinguishing
 * "server transient error" from other failures; {@code RestClient.retrieve().body()}
 * will surface it just like any other {@code RuntimeException}.
 */
@Getter
public class TransientHttpException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String statusText;
    private final URI uri;

    public TransientHttpException(int statusCode, String statusText, URI uri) {
        super("Transient HTTP " + statusCode + " " + statusText + " from " + uri);
        this.statusCode = statusCode;
        this.statusText = statusText;
        this.uri = uri;
    }
}
