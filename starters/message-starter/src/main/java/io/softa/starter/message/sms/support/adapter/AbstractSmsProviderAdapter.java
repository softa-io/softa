package io.softa.starter.message.sms.support.adapter;

import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;

/**
 * Base class for {@link SmsProviderAdapter} implementations.
 * <p>
 * A template-method {@link #send} wraps {@link #doSend} with uniform exception
 * handling and logging. Shared HTTP plumbing lives here so each concrete adapter
 * only expresses what is genuinely provider-specific — endpoint, auth header,
 * request/response shape, error-field paths:
 * <ul>
 *   <li>{@link #resolveBaseUrl} — the configured endpoint, or the provider default</li>
 *   <li>{@link #postJson} — POST a JSON body (± headers) and parse the response</li>
 *   <li>{@link #parseJson} — null-safe body → {@link JsonNode}</li>
 *   <li>{@link #parseErrorBody} — parse a provider error body, else a generic HTTP failure</li>
 * </ul>
 * Providers whose request is not JSON (Twilio and Aliyun use form-encoding) build
 * their own {@code POST} but still share {@link #parseJson} / {@link #parseErrorBody}.
 */
@Slf4j
public abstract class AbstractSmsProviderAdapter implements SmsProviderAdapter {

    protected final RestClient restClient;

    protected AbstractSmsProviderAdapter(RestClient smsRestClient) {
        this.restClient = smsRestClient;
    }

    @Override
    public final SmsSendResult send(SmsProviderConfig config, SmsAdapterRequest request) {
        try {
            return doSend(config, request);
        } catch (RestClientResponseException e) {
            log.error("{} SMS send failed: HTTP {}", getProvider(), e.getStatusCode().value());
            return handleHttpError(e);
        } catch (Exception e) {
            log.error("{} SMS send failed: {}", getProvider(), e.getMessage());
            return SmsSendResult.failure(null,
                    getProvider() + " API call failed: " + e.getMessage());
        }
    }

    /**
     * Provider-specific send logic. Implementations may throw any exception;
     * the base {@link #send} method handles all error wrapping.
     */
    protected abstract SmsSendResult doSend(SmsProviderConfig config,
                                            SmsAdapterRequest request) throws Exception;

    // ------------------------------------------------------------------
    // Shared HTTP plumbing
    // ------------------------------------------------------------------

    /** Resolve the API base URL: the configured endpoint, or the provider default. */
    protected String resolveBaseUrl(SmsProviderConfig config, String defaultEndpoint) {
        return StringUtils.hasText(config.getApiEndpoint()) ? config.getApiEndpoint() : defaultEndpoint;
    }

    /** POST a JSON body with no extra headers and parse the response. */
    protected JsonNode postJson(String url, String jsonBody) {
        return postJson(url, jsonBody, headers -> { });
    }

    /** POST a JSON body with custom headers and parse the response into a {@link JsonNode}. */
    protected JsonNode postJson(String url, String jsonBody, Consumer<HttpHeaders> headers) {
        String response = restClient.post()
                .uri(url)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .body(String.class);
        return parseJson(response);
    }

    /** Parse a (possibly empty) response body; blank/null → {@link NullNode} so {@code path()} stays null-safe. */
    protected JsonNode parseJson(String body) {
        if (!StringUtils.hasText(body)) {
            return NullNode.getInstance();
        }
        JsonNode node = JsonUtils.stringToObject(body, JsonNode.class);
        return node != null ? node : NullNode.getInstance();
    }

    // ------------------------------------------------------------------
    // Error handling
    // ------------------------------------------------------------------

    /**
     * Convert an HTTP error response into a {@link SmsSendResult}. The default is a
     * generic HTTP-status failure; adapters override to parse provider-specific error
     * JSON, typically via {@link #parseErrorBody}.
     */
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        return defaultHttpError(e);
    }

    /**
     * Parse a provider error body with a custom {@code extractor}, falling back to a
     * generic HTTP-status failure when the body is missing or unparseable. Collapses the
     * try/parse/catch wrapper each adapter's {@link #handleHttpError} would otherwise repeat.
     */
    protected SmsSendResult parseErrorBody(RestClientResponseException e,
                                           Function<JsonNode, SmsSendResult> extractor) {
        try {
            JsonNode err = JsonUtils.stringToObject(e.getResponseBodyAsString(), JsonNode.class);
            if (err != null) {
                return extractor.apply(err);
            }
        } catch (Exception parseEx) {
            // fall through to the generic HTTP failure
        }
        return defaultHttpError(e);
    }

    /** The terminal fallback: a generic failure carrying the HTTP status. */
    protected final SmsSendResult defaultHttpError(RestClientResponseException e) {
        return SmsSendResult.failure("HTTP_" + e.getStatusCode().value(),
                getProvider() + " API error (HTTP " + e.getStatusCode().value() + ")");
    }

    // ------------------------------------------------------------------
    // Misc helpers
    // ------------------------------------------------------------------

    protected String resolveSender(SmsProviderConfig config) {
        return StringUtils.hasText(config.getSenderNumber())
                ? config.getSenderNumber() : config.getSenderId();
    }

    protected String stripPlusPrefix(String phoneNumber) {
        return phoneNumber != null && phoneNumber.startsWith("+")
                ? phoneNumber.substring(1) : phoneNumber;
    }
}
