package io.softa.starter.message.sms.support.adapter;

import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.enums.SmsProvider;

/**
 * Adapter interface for sending SMS through a specific provider.
 * <p>
 * Each implementation handles the HTTP communication with a single SMS provider
 * (e.g. Twilio, Infobip). Implementations are registered as Spring {@code @Component}
 * beans and auto-discovered by {@link io.softa.starter.message.sms.support.SmsAdapterFactory}.
 * <p>
 * To add a new SMS provider:
 * <ol>
 *   <li>Add a value to {@link SmsProvider}</li>
 *   <li>Create a new {@code @Component} extending {@link AbstractSmsProviderAdapter}</li>
 * </ol>
 */
public interface SmsProviderAdapter {

    /**
     * @return the provider type this adapter handles
     */
    SmsProvider getProvider();

    /**
     * Send an SMS message through the provider.
     *
     * @param config  provider configuration with credentials
     * @param request adapter-level request with phone number, content, and template info
     * @return the send result with success status and provider message ID
     */
    SmsSendResult send(SmsProviderConfig config, SmsAdapterRequest request);
}
