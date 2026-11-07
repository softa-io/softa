package io.softa.starter.message.mail.service.impl;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.utils.HtmlUtils;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.MailTemplateService;
import io.softa.starter.message.mail.smtp.SmtpMailRequest;
import io.softa.starter.message.mail.smtp.SmtpMailTransport;
import io.softa.starter.message.mail.smtp.SmtpSendResult;
import io.softa.starter.message.mail.support.MailAddresses;
import io.softa.starter.message.mail.support.MailBodies;
import io.softa.starter.message.mail.support.MailServerDispatcher;
import io.softa.starter.message.shared.ErrorClassifier;
import io.softa.starter.message.shared.MonthlyQuotaGuard;
import io.softa.starter.message.shared.RateLimiter;
import io.softa.starter.message.shared.SendRejection;
import io.softa.starter.message.shared.metrics.MessageMetrics;
import io.softa.starter.message.shared.retry.SendFailureHandler;

/**
 * Delivery execution for mail: prepare the record on its first attempt, rate-limit, deliver over
 * SMTP, and route every failure through {@link SendFailureHandler}.
 * <p>
 * The delivery loop claims a record (PENDING / RETRY → SENDING) before handing it here, so one worker
 * at a time owns it. The first attempt <b>prepares</b> the record: it renders the template the request
 * named and applies its defaults, chooses the server — the caller's or the template's pin, else the
 * scope's default — and charges the monthly quota, last, so a send that cannot go out costs nothing.
 * The result is written back and retries replay it verbatim. Whatever stops preparation (no server, a
 * missing or broken template, the quota used up, empty content) fails the record with a marker code
 * and an ERROR log; the caller already has its record id and never sees an exception.
 * <p>
 * Deliberately separate from the public {@code MessageService}: the service is the <i>acceptance</i>
 * side (store the request and return), this processor the <i>execution</i> side.
 */
@Slf4j
@Component
public class MailDeliveryProcessor {

    @Autowired
    private MailServerDispatcher dispatcher;

    @Autowired
    private MailTemplateService templateService;

    @Autowired
    private MonthlyQuotaGuard quotaGuard;

    @Autowired
    private MessageProperties messageProperties;

    @Autowired
    private SmtpMailTransport smtpMailTransport;

    @Autowired
    private MailSendRecordService recordService;

    @Autowired(required = false)
    private MessageMetrics metrics;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private SendFailureHandler sendFailureHandler;

    /**
     * Deliver one record the delivery loop has claimed: it is SENDING at {@code version}, and only
     * this call moves it on. Runs in the record's tenant context.
     */
    public void deliver(MailSendRecord record, long version) {
        MailSendServerConfig config;
        try {
            if (isPrepared(record)) {
                config = resolveConfig(record.getServerConfigId(), null);
            } else {
                config = prepare(record);
                if (!recordService.markPrepared(record, version)) {
                    log.debug("MailDeliveryProcessor: record id={} changed while it was being prepared; "
                            + "leaving it to the newer state", record.getId());
                    return;
                }
                version++;
            }
        } catch (SendRejection e) {
            handleFailure(record, version, e.code(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            // Not a configuration verdict (a database or cache hiccup): keep the stack and let the
            // retry policy try again.
            log.error("MailDeliveryProcessor: preparing record id={} failed unexpectedly", record.getId(), e);
            handleFailure(record, version, null, e.getMessage());
            return;
        }
        send(record, version, config);
    }

    /**
     * Only preparation writes {@code fromAddress} — a caller cannot set it — so a record that has one
     * was prepared, and its content, server and sender are fixed.
     */
    private static boolean isPrepared(MailSendRecord record) {
        return StringUtils.hasText(record.getFromAddress());
    }

    /**
     * Fix on {@code record} what the first attempt decides: rendered content and template defaults,
     * the server and the sender; then charge the month's quota.
     */
    private MailSendServerConfig prepare(MailSendRecord record) {
        MessageScope scope = Boolean.TRUE.equals(record.getPlatformScope())
                ? MessageScope.PLATFORM : MessageScope.TENANT;
        MailTemplate template = loadTemplate(record, scope);
        if (template != null) {
            applyTemplate(record, template);
        }
        requireSendableContent(record);
        MailSendServerConfig config = resolveConfig(record.getServerConfigId(), scope);
        String sender = StringUtils.hasText(config.getFromAddress()) ? config.getFromAddress() : config.getUsername();
        if (!StringUtils.hasText(sender)) {
            throw new SendRejection(ErrorClassifier.CONFIG_NOT_RESOLVABLE, "Mail send server config "
                    + config.getId() + " has neither a from address nor a username to send as.");
        }
        try {
            quotaGuard.consume("mail", MonthlyQuotaGuard.bucketFor(scope));
        } catch (BusinessException e) {
            throw new SendRejection(ErrorClassifier.MONTHLY_QUOTA_EXCEEDED, e.getMessage());
        }
        record.setServerConfigId(config.getId());
        record.setFromAddress(sender);
        // Third tier of the Reply-To chain (request > template > server); fixed here so retries replay it.
        if (!StringUtils.hasText(record.getReplyTo())) {
            record.setReplyTo(config.getReplyToAddress());
        }
        record.setReadReceiptRequested(Boolean.TRUE.equals(record.getReadReceiptRequested() != null
                ? record.getReadReceiptRequested() : config.getReadReceiptEnabled()));
        return config;
    }

    /**
     * The template the request named: {@code templateId} addresses the exact row (editor test sends:
     * what you preview is what goes out, enabled or not); {@code templateCode} resolves in the scope's
     * tier, enabled rows only, no fallback across tiers.
     */
    private MailTemplate loadTemplate(MailSendRecord record, MessageScope scope) {
        if (record.getTemplateId() == null && !StringUtils.hasText(record.getTemplateCode())) {
            return null;
        }
        try {
            return record.getTemplateId() != null
                    ? templateService.getRequiredById(record.getTemplateId())
                    : templateService.resolve(record.getTemplateCode(), scope);
        } catch (BusinessException e) {
            throw new SendRejection(ErrorClassifier.TEMPLATE_NOT_RESOLVABLE, e.getMessage());
        }
    }

    /**
     * Render what the caller left empty and apply the template's defaults — caller values always win.
     * Defaults land before the server is resolved, so a template's preferred server is honored.
     */
    private void applyTemplate(MailSendRecord record, MailTemplate template) {
        Map<String, Object> variables = record.getTemplateVariables() == null
                ? Map.of() : JsonUtils.jsonNodeToMap(record.getTemplateVariables());
        try {
            if (!StringUtils.hasText(record.getSubject())) {
                record.setSubject(templateService.renderSubject(template, variables));
            }
            if (!StringUtils.hasText(record.getBodyHtml()) && !StringUtils.hasText(record.getBodyText())) {
                renderBody(record, template, variables);
            }
        } catch (RuntimeException e) {
            throw new SendRejection(ErrorClassifier.TEMPLATE_NOT_RESOLVABLE,
                    "Template '" + template.getCode() + "' failed to render: " + e.getMessage());
        }
        if (record.getPriority() == null && template.getDefaultPriority() != null) {
            record.setPriority(template.getDefaultPriority());
        }
        if (!StringUtils.hasText(record.getReplyTo()) && StringUtils.hasText(template.getReplyTo())) {
            record.setReplyTo(MailAddresses.normalizeAddressList(template.getReplyTo()));
        }
        if (CollectionUtils.isEmpty(record.getAttachments()) && !CollectionUtils.isEmpty(template.getAttachments())) {
            record.setAttachments(template.getAttachments());
        }
        if (record.getServerConfigId() == null && template.getPreferredServerConfigId() != null) {
            record.setServerConfigId(template.getPreferredServerConfigId());
        }
    }

    /**
     * Render the template's bodies as its {@link BodyMode} says. For
     * {@link BodyMode#HTML_WITH_AUTHORED_PLAIN} with an empty plain body (the editor is supposed to
     * require it — defensive backstop) the plain text is derived from the HTML and the mode downgraded
     * to {@link BodyMode#HTML_WITH_DERIVED_PLAIN}, so the record truthfully says it went through
     * machine extraction.
     */
    private void renderBody(MailSendRecord record, MailTemplate template, Map<String, Object> variables) {
        BodyMode mode = template.getBodyMode() != null ? template.getBodyMode() : BodyMode.HTML;
        String html = templateService.renderBodyHtml(template, variables);
        String text = templateService.renderBodyText(template, variables);
        switch (mode) {
            case PLAIN -> setBody(record, null, text, mode);
            case HTML -> setBody(record, html, null, mode);
            case HTML_WITH_DERIVED_PLAIN -> setBody(record, html, html != null ? HtmlUtils.toText(html) : null, mode);
            case HTML_WITH_AUTHORED_PLAIN -> {
                if (StringUtils.hasText(text)) {
                    setBody(record, html, text, mode);
                } else {
                    log.warn("Template '{}' is HTML_WITH_AUTHORED_PLAIN but bodyText is empty; "
                            + "falling back to derived plain text. Record will be marked DERIVED.",
                            template.getCode());
                    setBody(record, html, html != null ? HtmlUtils.toText(html) : null,
                            BodyMode.HTML_WITH_DERIVED_PLAIN);
                }
            }
        }
    }

    private static void setBody(MailSendRecord record, String html, String text, BodyMode mode) {
        record.setBodyHtml(StringUtils.hasText(html) ? html : null);
        record.setBodyText(StringUtils.hasText(text) ? text : null);
        record.setBodyMode(mode);
    }

    /** The prepared content must be sendable: a body within the size cap and a well-formed Reply-To. */
    private void requireSendableContent(MailSendRecord record) {
        if (!StringUtils.hasText(record.getBodyHtml()) && !StringUtils.hasText(record.getBodyText())) {
            throw new SendRejection(ErrorClassifier.CONTENT_INVALID,
                    "No content: the template rendered an empty body.");
        }
        try {
            MailBodies.requireWithinLimit(record.getBodyHtml(), record.getBodyText(),
                    messageProperties.getMail().getMaxBodyChars());
            MailAddresses.requireValidAddressList("replyTo", record.getReplyTo());
        } catch (BusinessException e) {
            throw new SendRejection(ErrorClassifier.CONTENT_INVALID, e.getMessage());
        }
        record.setBodyMode(MailBodies.modeOf(record.getBodyMode(), record.getBodyHtml(), record.getBodyText()));
    }

    /**
     * An explicit id (caller, template pin, or the record's prepared server) wins over the tier policy;
     * {@code scope} applies only when there is none.
     */
    private MailSendServerConfig resolveConfig(Long serverConfigId, MessageScope scope) {
        try {
            return serverConfigId != null
                    ? dispatcher.resolveSendById(serverConfigId)
                    : dispatcher.resolveSend(scope);
        } catch (BusinessException e) {
            throw new SendRejection(ErrorClassifier.CONFIG_NOT_RESOLVABLE, e.getMessage());
        }
    }

    /**
     * Rate-limit, then deliver. Funnels every failed send through {@link #handleFailure}. A send the
     * server accepted is never a failure: if recording it as SENT fails, the record is left SENDING
     * (see the catch below).
     */
    private void send(MailSendRecord record, long version, MailSendServerConfig config) {
        RateLimiter.Outcome quota = rateLimiter.tryAcquire("mail", config.getId(),
                config.getDailySendLimit(), config.getRateLimitPerMinute());
        if (!quota.ok()) {
            handleFailure(record, version, "QUOTA_EXCEEDED", "Rate limit: " + quota.name());
            return;
        }

        SmtpMailRequest request = buildRequest(rebuildDTO(record), config);
        SmtpSendResult result;
        int sendDurationMs;
        try {
            long started = System.nanoTime();
            result = smtpMailTransport.send(config, request);
            sendDurationMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        } catch (Exception e) {
            // Known SMTP failures arrive as SmtpSendResult with a code; anything
            // reaching this catch is unexpected — keep the stack in the log.
            log.error("MailDeliveryProcessor: unexpected send failure for record id={}",
                    record.getId(), e);
            handleFailure(record, version, null, e.getMessage());
            return;
        }
        if (!result.isSuccess()) {
            handleFailure(record, version, result.getErrorCode(), result.getErrorMessage());
            return;
        }
        if (metrics != null) metrics.sent("mail", SmtpMailTransport.NAME);
        try {
            recordService.markSent(record.getId(), version,
                    result.getMessageId(), SmtpMailTransport.NAME, sendDurationMs);
        } catch (RuntimeException e) {
            // The server has accepted the mail, so this is not a send failure: routing
            // it through handleFailure would schedule a retry and send the mail again.
            // The record stays SENDING; the zombie sweeper revives it after
            // stale-seconds, and that one re-send is the cost of at-least-once delivery.
            log.error("MailDeliveryProcessor: mail delivered but recording it as sent failed; "
                    + "record id={} stays SENDING (messageId={})",
                    record.getId(), result.getMessageId(), e);
        }
    }

    private void handleFailure(MailSendRecord record, long version, String errorCode, String errorMessage) {
        int currentAttempts = record.getRetryCount() != null ? record.getRetryCount() : 0;
        sendFailureHandler.handle("mail", SmtpMailTransport.NAME,
                record.getId(), version, currentAttempts, errorCode, errorMessage,
                new SendFailureHandler.RecordTransitions(
                        recordService::markRetry, recordService::markFailed, recordService::markDeadLetter));
    }

    /** Map service-layer DTO + server config onto an SMTP send request. */
    private SmtpMailRequest buildRequest(SendMailDTO dto, MailSendServerConfig config) {
        SmtpMailRequest req = new SmtpMailRequest();
        String fromAddress = StringUtils.hasText(config.getFromAddress())
                ? config.getFromAddress() : config.getUsername();
        req.setFromAddress(fromAddress);
        req.setFromName(StringUtils.hasText(config.getFromName()) ? config.getFromName() : fromAddress);
        req.setTo(dto.getTo());
        req.setCc(dto.getCc());
        req.setBcc(dto.getBcc());
        // dto.replyTo is already the final value (fixed into the record when it
        // was prepared; rebuildDTO restores it). No fallback needed here.
        req.setReplyTo(dto.getReplyTo());
        req.setSubject(dto.getSubject());
        req.setHtmlBody(dto.getHtmlBody());
        req.setTextBody(dto.getTextBody());
        req.setAttachments(dto.getAttachments());
        req.setPriority(dto.getPriority());
        req.setReadReceiptRequested(Boolean.TRUE.equals(
                dto.getReadReceiptRequested() != null
                        ? dto.getReadReceiptRequested()
                        : config.getReadReceiptEnabled()));
        return req;
    }

    private SendMailDTO rebuildDTO(MailSendRecord record) {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(record.getToAddresses());
        dto.setCc(record.getCcAddresses());
        dto.setBcc(record.getBccAddresses());
        dto.setSubject(record.getSubject());
        dto.setServerConfigId(record.getServerConfigId());
        // Verbatim replay: both columns are persisted, no retry-time derivation.
        // This guarantees byte-for-byte SMTP fidelity even if HtmlUtils.toText
        // changes between first send and a much-later retry.
        dto.setBodyMode(record.getBodyMode());
        dto.setHtmlBody(record.getBodyHtml());
        dto.setTextBody(record.getBodyText());
        if (Boolean.TRUE.equals(record.getReadReceiptRequested())) {
            dto.setReadReceiptRequested(true);
        }
        dto.setPriority(record.getPriority());
        dto.setReplyTo(record.getReplyTo());
        dto.setAttachments(record.getAttachments());
        return dto;
    }
}
