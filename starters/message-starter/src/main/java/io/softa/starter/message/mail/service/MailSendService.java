package io.softa.starter.message.mail.service;

import io.softa.starter.message.mail.dto.SendMailDTO;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Public interface for sending emails.
 * <p>
 * The implementation automatically resolves the appropriate mail server config
 * (tenant-level if configured, otherwise platform-level fallback) via
 * {@link io.softa.starter.message.mail.support.MailServerDispatcher}.
 * <p>
 * Usage example:
 * <pre>{@code
 * @Autowired
 * private MailSendService mailSendService;
 *
 * mailSendService.sendHtml("user@example.com", "Welcome", "<h1>Hello!</h1>");
 * }</pre>
 */
public interface MailSendService {

    /**
     * Send a plain-text email to a single recipient using the resolved server config.
     */
    void sendText(String to, String subject, String body);

    /**
     * Send a plain-text email to multiple recipients.
     */
    void sendText(List<String> to, String subject, String body);

    /**
     * Send an HTML email to a single recipient.
     */
    void sendHtml(String to, String subject, String htmlBody);

    /**
     * Send an HTML email to multiple recipients.
     */
    void sendHtml(List<String> to, String subject, String htmlBody);

    /**
     * Send an email with full control over recipients, body, attachments and server selection.
     */
    void sendNow(SendMailDTO dto);

    /**
     * Send an email asynchronously. Returns immediately; delivery happens in the background.
     */
    CompletableFuture<Void> sendAsync(SendMailDTO dto);

    /**
     * Resolve a mail template by {@code code}, render it with {@code variables},
     * and send to a single recipient.
     * <p>
     * The server config is resolved automatically (tenant → platform fallback).
     *
     * @param code      template code, e.g. {@code "USER_WELCOME"}
     * @param to        recipient address
     * @param variables placeholder values substituted into the template subject and body
     */
    void sendByTemplate(String code, String to, Map<String, Object> variables);

    /**
     * Resolve a mail template by {@code code}, render it with {@code variables},
     * and send to multiple recipients.
     *
     * @param code      template code, e.g. {@code "ORDER_CONFIRMATION"}
     * @param to        recipient addresses
     * @param variables placeholder values substituted into the template subject and body
     */
    void sendByTemplate(String code, List<String> to, Map<String, Object> variables);

    /**
     * Retry sending a previously failed email identified by its {@code MailSendRecord} ID.
     * <p>
     * Called by the Pulsar retry consumer after a delayed delivery. If the record
     * is no longer in {@code RETRY} status, the call is silently ignored.
     *
     * @param sendRecordId primary key of the {@link io.softa.starter.message.mail.entity.MailSendRecord}
     */
    void retrySend(Long sendRecordId);
}
