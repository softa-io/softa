package io.softa.starter.message.mail.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.smtp.SmtpMailRequest;
import io.softa.starter.message.mail.smtp.SmtpMailTransport;
import io.softa.starter.message.mail.smtp.SmtpSendResult;
import io.softa.starter.message.mail.support.MailServerDispatcher;
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.shared.RateLimiter;
import io.softa.starter.message.shared.metrics.MessageMetrics;
import io.softa.starter.message.shared.retry.SendFailureHandler;

/**
 * Consumer-side delivery execution for mail: claim the record via CAS
 * (PENDING/RETRY → SENDING), rate-limit, deliver over SMTP, and route every
 * failure mode through {@link SendFailureHandler}.
 * <p>
 * Deliberately separate from the public {@code MessageService}: the service is the
 * <i>acceptance</i> side (normalize request → persist PENDING + outbox row and
 * return), this processor is the <i>execution</i> side driven by the broker
 * consumers and the manual-retry API. Duplicate broker deliveries are rejected
 * by the CAS claim, so the processor is safe to invoke any number of times per
 * record.
 */
@Slf4j
@Component
public class MailDeliveryProcessor {

    @Autowired
    private MailServerDispatcher dispatcher;

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
     * Load, claim, and deliver one send record. No-ops when the record is
     * missing or already terminal / in flight (duplicate delivery).
     */
    public void process(Long recordId) {
        MailSendRecord record = recordService.getById(recordId).orElse(null);
        if (record == null) {
            log.warn("MailDeliveryProcessor: record not found id={} (duplicate or TTL-evicted)", recordId);
            return;
        }
        MailSendStatus s = record.getStatus();
        if (s != MailSendStatus.PENDING && s != MailSendStatus.RETRY) {
            log.debug("MailDeliveryProcessor: record id={} already in terminal/sending state {}, ignoring",
                    recordId, s);
            return;
        }
        long expectedVersion = record.getVersion() != null ? record.getVersion() : 0L;

        MailSendServerConfig config;
        try {
            config = dispatcher.resolveSendById(record.getServerConfigId());
        } catch (Exception e) {
            if (recordService.casStatus(recordId, expectedVersion, MailSendStatus.SENDING)) {
                handleFailure(record, expectedVersion + 1, null,
                        "Config not resolvable: " + e.getMessage(), SmtpMailTransport.NAME);
            }
            return;
        }
        executeSend(record, expectedVersion, config);
    }

    /**
     * Claim the record via CAS (PENDING/RETRY → SENDING) and deliver.
     * Returns early on CAS miss (duplicate delivery) and funnels all failure
     * modes through {@link #handleFailure} under {@code expectedVersion + 1}.
     */
    private void executeSend(MailSendRecord record, long expectedVersion,
                             MailSendServerConfig config) {
        if (!recordService.casStatus(record.getId(), expectedVersion, MailSendStatus.SENDING)) {
            log.debug("executeSend: CAS miss for id={} v={}, treating as duplicate",
                    record.getId(), expectedVersion);
            return;
        }
        long sendingVersion = expectedVersion + 1;

        RateLimiter.Outcome quota = rateLimiter.tryAcquire("mail", config.getId(),
                config.getDailySendLimit(), config.getRateLimitPerMinute());
        if (!quota.ok()) {
            handleFailure(record, sendingVersion, "QUOTA_EXCEEDED",
                    "Rate limit: " + quota.name(), SmtpMailTransport.NAME);
            return;
        }

        SmtpMailRequest request = buildRequest(rebuildDTO(record), config);
        try {
            SmtpSendResult result = smtpMailTransport.send(config, request);
            if (result.isSuccess()) {
                recordService.markSent(record.getId(), sendingVersion,
                        result.getMessageId(), SmtpMailTransport.NAME);
                if (metrics != null) metrics.sent("mail", SmtpMailTransport.NAME);
            } else {
                handleFailure(record, sendingVersion,
                        result.getErrorCode(), result.getErrorMessage(), SmtpMailTransport.NAME);
            }
        } catch (Exception e) {
            handleFailure(record, sendingVersion, null, e.getMessage(), SmtpMailTransport.NAME);
        }
    }

    private void handleFailure(MailSendRecord record, long expectedVersion,
                               String errorCode, String errorMessage, String transportName) {
        int currentAttempts = record.getRetryCount() != null ? record.getRetryCount() : 0;
        sendFailureHandler.handle("mail", transportName, TopicRoute.MAIL_SEND, "MailSendRecord",
                record.getId(), expectedVersion, currentAttempts, errorCode, errorMessage,
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
        // dto.replyTo is already the final value (resolved into the record at
        // accept time; rebuildDTO restores it). No fallback needed here.
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
