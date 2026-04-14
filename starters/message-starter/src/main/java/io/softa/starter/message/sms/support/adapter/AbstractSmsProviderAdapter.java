package io.softa.starter.message.sms.support.adapter;

import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Base class for {@link SmsProviderAdapter} implementations.
 * <p>
 * Provides a template-method {@link #send} that wraps {@link #doSend} with
 * uniform exception handling and logging. Subclasses only need to implement
 * the provider-specific HTTP call in {@link #doSend} and may override
 * {@link #handleHttpError} for custom error-response parsing.
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

    /**
     * Converts an HTTP error response into a {@link SmsSendResult}.
     * The default implementation returns a generic failure with the HTTP status code.
     * Override in adapters that parse provider-specific error JSON bodies.
     */
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        String errorCode = "HTTP_" + e.getStatusCode().value();
        return SmsSendResult.failure(errorCode,
                getProvider() + " API error (HTTP " + e.getStatusCode().value() + ")");
    }

    protected String resolveSender(SmsProviderConfig config) {
        return StringUtils.hasText(config.getSenderNumber())
                ? config.getSenderNumber() : config.getSenderId();
    }

    protected String stripPlusPrefix(String phoneNumber) {
        return phoneNumber != null && phoneNumber.startsWith("+")
                ? phoneNumber.substring(1) : phoneNumber;
    }
}
