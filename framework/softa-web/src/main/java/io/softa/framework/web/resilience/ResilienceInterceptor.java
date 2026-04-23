package io.softa.framework.web.resilience;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.function.Function;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.retry.Retry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * {@link ClientHttpRequestInterceptor} that wraps each outgoing request with a
 * Resilience4j {@code Retry} and {@code CircuitBreaker}.
 * <p>
 * Both decorators are exception-driven, so HTTP responses with a "retryable" status
 * code ({@code 5xx}, {@code 408}, {@code 429} by default) are re-thrown as a
 * {@link TransientHttpException} inside the supplier; the {@code Retry} config is
 * expected to include that type in {@code retry-exceptions}, and the
 * {@code CircuitBreaker} counts the failure.
 */
@Slf4j
public final class ResilienceInterceptor implements ClientHttpRequestInterceptor {

    private final Retry retry;
    private final Function<URI, CircuitBreaker> cbLookup;
    private final Set<Integer> retryableStatuses;

    public ResilienceInterceptor(Retry retry,
                                 Function<URI, CircuitBreaker> cbLookup,
                                 Set<Integer> retryableStatuses) {
        this.retry = retry;
        this.cbLookup = cbLookup;
        this.retryableStatuses = Set.copyOf(retryableStatuses);
    }

    @Override
    public @NonNull ClientHttpResponse intercept(HttpRequest request, byte @NonNull [] body,
                                                 @NonNull ClientHttpRequestExecution execution) throws IOException {
        URI uri = request.getURI();
        CircuitBreaker circuitBreaker = cbLookup.apply(uri);

        CheckedSupplier<ClientHttpResponse> executeWithStatusCheck = () -> {
            ClientHttpResponse response = execution.execute(request, body);
            int status = response.getStatusCode().value();
            if (retryableStatuses.contains(status)) {
                String statusText = safeStatusText(response);
                safeClose(response);
                throw new TransientHttpException(status, statusText, uri);
            }
            return response;
        };

        CheckedSupplier<ClientHttpResponse> decorated = CircuitBreaker.decorateCheckedSupplier(
                circuitBreaker,
                Retry.decorateCheckedSupplier(retry, executeWithStatusCheck));

        try {
            return decorated.get();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Resilient HTTP call to " + uri + " failed", t);
        }
    }

    private static String safeStatusText(ClientHttpResponse response) {
        try {
            return response.getStatusText();
        } catch (Exception e) {
            return "";
        }
    }

    private static void safeClose(ClientHttpResponse response) {
        try {
            response.close();
        } catch (Exception e) {
            log.debug("Failed to close retryable response", e);
        }
    }
}
