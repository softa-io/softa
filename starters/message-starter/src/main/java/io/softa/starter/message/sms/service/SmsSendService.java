package io.softa.starter.message.sms.service;

import io.softa.starter.message.sms.dto.SendSmsDTO;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Public interface for sending SMS messages.
 * <p>
 * The implementation automatically resolves the appropriate SMS provider config
 * (tenant-level if configured, otherwise platform-level fallback) via
 * {@link io.softa.starter.message.sms.support.SmsProviderDispatcher}.
 * <p>
 * Usage example:
 * <pre>{@code
 * @Autowired
 * private SmsSendService smsSendService;
 *
 * smsSendService.sendNow("+1234567890", "Your verification code is 123456");
 * }</pre>
 */
public interface SmsSendService {

    /**
     * Send a plain-text SMS to a single recipient using the resolved provider config.
     */
    void sendNow(String phoneNumber, String content);

    /**
     * Send an SMS with full control over the request parameters.
     * <p>
     * Supports three modes:
     * <ul>
     *   <li><b>Single send:</b> set {@code phoneNumber} and {@code content}</li>
     *   <li><b>Uniform batch:</b> set {@code phoneNumbers} and {@code content} (same content to all)</li>
     *   <li><b>Differentiated batch:</b> set {@code items} with per-recipient content/variables</li>
     * </ul>
     */
    void sendNow(SendSmsDTO dto);

    /**
     * Resolve an SMS template by {@code code}, render it with {@code variables},
     * and send to a single recipient.
     *
     * @param code      template code, e.g. {@code "VERIFY_CODE"}
     * @param phoneNumber recipient phone number
     * @param variables placeholder values substituted into the template content
     */
    void sendByTemplate(String code, String phoneNumber, Map<String, Object> variables);

    /**
     * Resolve an SMS template by {@code code}, render it with {@code variables},
     * and send to multiple recipients.
     *
     * @param code         template code
     * @param phoneNumbers recipient phone numbers
     * @param variables    placeholder values substituted into the template content
     */
    void sendByTemplate(String code, List<String> phoneNumbers, Map<String, Object> variables);

    /**
     * Send an SMS asynchronously via message queue (Pulsar).
     * <p>
     * If the Pulsar SMS-send topic is configured ({@code mq.topics.sms-send.topic}),
     * the request is published to the queue for background processing.
     * Otherwise, falls back to a thread-pool-based {@code @Async} send.
     * <p>
     * Returns immediately in both cases.
     */
    CompletableFuture<Void> sendAsync(SendSmsDTO dto);

    /**
     * Retry sending a previously failed SMS identified by its {@code SmsSendRecord} ID.
     * <p>
     * Called by the Pulsar retry consumer after a delayed delivery. If the record
     * is no longer in {@code RETRY} status, the call is silently ignored.
     *
     * @param sendRecordId primary key of the {@link io.softa.starter.message.sms.entity.SmsSendRecord}
     */
    void retrySend(Long sendRecordId);
}
