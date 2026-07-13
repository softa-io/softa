package io.softa.starter.message.service.internal;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.HtmlUtils;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.enums.MailPriority;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.MailTemplateService;
import io.softa.starter.message.mail.service.impl.MailDeliveryProcessor;
import io.softa.starter.message.mail.support.MailServerDispatcher;
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.mq.outbox.OutboxRecordWriter;

/**
 * Internal acceptance handler for one email message. The public
 * {@code MessageService} owns single/batch orchestration; this handler owns the
 * channel-specific normalization funnel (validation → template rendering →
 * template defaults). Normalization produces an immutable internal value and
 * never mutates the caller's DTO, then persists one
 * {@code MailSendRecord (PENDING)} + outbox row and returns
 * the record id immediately.
 * <p>
 * Delivery execution (CAS claim, rate-limit, SMTP, retry / DLQ) lives in
 * {@link MailDeliveryProcessor}, driven by the broker consumers; callers never
 * block on the SMTP round-trip and there is deliberately no synchronous
 * variant — an SMTP {@code 250 OK} is false-precision (the user still waits
 * for the provider to deliver), and a single enqueue-and-return path avoids
 * blocking HTTP threads.
 * <p>
 * Normalization contract (single funnel — the ordering is the point):
 * <ol>
 *   <li>Resolve the template once when {@code templateCode} is set.</li>
 *   <li>Render subject/body wherever the caller didn't supply explicit
 *       content (caller values always win over template values).</li>
 *   <li>Apply template defaults (priority / replyTo / attachments /
 *       preferredServerConfigId) <b>before</b> server-config resolution, so a
 *       template's preferred SMTP is honored on every path.</li>
 *   <li>Validate: recipients present, and a non-empty body after rendering.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
final class MailMessageHandler {

    private final MailServerDispatcher dispatcher;

    private final MailSendRecordService recordService;

    private final MailTemplateService templateService;

    private final OutboxRecordWriter outboxRecordWriter;

    Long send(SendMailDTO dto) {
        ResolvedMail message = resolve(dto);
        // Config resolution happens AFTER request resolution so a template's
        // preferredServerConfigId is honored.
        MailSendServerConfig config = resolveConfig(message);
        return enqueueForAsyncSend(message, config);
    }

    // ------------------------------------------------------------------
    // Normalization — the single funnel every entry point goes through
    // ------------------------------------------------------------------

    /**
     * Resolve template defaults and validate one email request.
     */
    private ResolvedMail resolve(SendMailDTO dto) {
        MailTemplate template = StringUtils.hasText(dto.getTemplateCode())
                ? templateService.resolve(dto.getTemplateCode())
                : null;

        String subject = dto.getSubject();
        String htmlBody = dto.getHtmlBody();
        String textBody = dto.getTextBody();
        BodyMode bodyMode = dto.getBodyMode();
        List<FileInfo> attachments = dto.getAttachments();
        Long serverConfigId = dto.getServerConfigId();
        String replyTo = dto.getReplyTo();
        MailPriority priority = dto.getPriority();

        if (template != null) {
            if (!StringUtils.hasText(subject)) {
                subject = templateService.renderSubject(template, dto.getTemplateVariables());
            }
            if (!StringUtils.hasText(htmlBody) && !StringUtils.hasText(textBody)) {
                ResolvedBody body = renderTemplateBody(template, dto.getTemplateVariables());
                htmlBody = body.html();
                textBody = body.text();
                bodyMode = body.mode();
            }
            if (priority == null && template.getDefaultPriority() != null) {
                priority = template.getDefaultPriority();
            }
            if (!StringUtils.hasText(replyTo) && StringUtils.hasText(template.getReplyTo())) {
                replyTo = template.getReplyTo();
            }
            if (CollectionUtils.isEmpty(attachments)
                    && !CollectionUtils.isEmpty(template.getAttachments())) {
                attachments = template.getAttachments();
            }
            if (serverConfigId == null && template.getPreferredServerConfigId() != null) {
                serverConfigId = template.getPreferredServerConfigId();
            }
        }

        ResolvedMail resolved = new ResolvedMail(
                immutableCopy(dto.getTo()), immutableCopy(dto.getCc()), immutableCopy(dto.getBcc()),
                subject, textBody, htmlBody, bodyMode, immutableCopy(attachments), serverConfigId,
                replyTo, dto.getReadReceiptRequested(), priority);
        requireRecipients(resolved.to(), "SendMailDTO.to");
        requireBody(resolved);
        return resolved;
    }

    private static void requireRecipients(List<String> to, String field) {
        if (CollectionUtils.isEmpty(to)) {
            throw new BusinessException("Mail send rejected: {0} must contain at least one recipient", field);
        }
    }

    private static void requireBody(ResolvedMail message) {
        if (!StringUtils.hasText(message.htmlBody()) && !StringUtils.hasText(message.textBody())) {
            throw new BusinessException(
                    "Mail send rejected: no content — provide htmlBody/textBody or a templateCode that renders one");
        }
    }

    /**
     * Render the template's body fields based on {@link MailTemplate#getBodyMode()}.
     *
     * <p>For {@link BodyMode#HTML_WITH_AUTHORED_PLAIN}: when the template's
     * {@code bodyText} is empty (front-end is supposed to enforce required, but
     * defensive backstop), we derive plain from HTML and downgrade the effective
     * mode to {@link BodyMode#HTML_WITH_DERIVED_PLAIN} — so the persisted record
     * truthfully reflects that the plain went through machine extraction.
     */
    private ResolvedBody renderTemplateBody(MailTemplate template, Map<String, Object> variables) {
        BodyMode mode = template.getBodyMode() != null ? template.getBodyMode() : BodyMode.HTML;
        String html = templateService.renderBodyHtml(template, variables);
        String text = templateService.renderBodyText(template, variables);
        return switch (mode) {
            case PLAIN -> new ResolvedBody(null, text, mode);
            case HTML -> new ResolvedBody(html, null, mode);
            case HTML_WITH_DERIVED_PLAIN -> new ResolvedBody(
                    html, html != null ? HtmlUtils.toText(html) : null, mode);
            case HTML_WITH_AUTHORED_PLAIN -> {
                if (StringUtils.hasText(text)) {
                    yield new ResolvedBody(html, text, mode);
                } else {
                    log.warn("Template '{}' is HTML_WITH_AUTHORED_PLAIN but bodyText is empty; "
                            + "falling back to derived plain text. Record will be marked DERIVED.",
                            template.getCode());
                    yield new ResolvedBody(html, html != null ? HtmlUtils.toText(html) : null,
                            BodyMode.HTML_WITH_DERIVED_PLAIN);
                }
            }
        };
    }

    // ------------------------------------------------------------------
    // Persistence — PENDING record + outbox row, atomically
    // ------------------------------------------------------------------

    private MailSendServerConfig resolveConfig(ResolvedMail message) {
        return message.serverConfigId() != null
                ? dispatcher.resolveSendById(message.serverConfigId())
                : dispatcher.resolveSend();
    }

    /**
     * Persist PENDING record + outbox row inside a single transaction. The
     * transaction boundary lives on {@link OutboxRecordWriter} so it is crossed
     * through the Spring proxy (a {@code @Transactional} method on this bean
     * would be bypassed by self-invocation).
     */
    private Long enqueueForAsyncSend(ResolvedMail message, MailSendServerConfig config) {
        return outboxRecordWriter.persistAndEnqueue(
                () -> {
                    MailSendRecord record = buildRecord(message, config);
                    return recordService.createOne(record);
                },
                "MailSendRecord", TopicRoute.MAIL_SEND);
    }

    private MailSendRecord buildRecord(ResolvedMail message, MailSendServerConfig config) {
        BodyMode mode = resolveBodyMode(message);

        MailSendRecord record = new MailSendRecord();
        record.setServerConfigId(config.getId());
        record.setFromAddress(config.getFromAddress());
        record.setToAddresses(message.to());
        record.setCcAddresses(message.cc());
        record.setBccAddresses(message.bcc());
        record.setSubject(message.subject());
        record.setBodyMode(mode);
        // Persist whichever bodies are present verbatim; retries replay them
        // bit-for-bit, no derivation at retry time.
        record.setBodyHtml(StringUtils.hasText(message.htmlBody()) ? message.htmlBody() : null);
        record.setBodyText(StringUtils.hasText(message.textBody()) ? message.textBody() : null);
        record.setAttachments(CollectionUtils.isEmpty(message.attachments()) ? null : message.attachments());
        record.setStatus(MailSendStatus.PENDING);
        record.setRetryCount(0);
        record.setVersion(0L);

        boolean requestReceipt = Boolean.TRUE.equals(
                message.readReceiptRequested() != null
                        ? message.readReceiptRequested()
                        : config.getReadReceiptEnabled());
        record.setReadReceiptRequested(requestReceipt);

        record.setPriority(message.priority());
        // Resolve final Reply-To using the third tier (config) so the record
        // captures the exact value used at first send. Retries replay it
        // verbatim — no risk of config drift between send and retry.
        record.setReplyTo(StringUtils.hasText(message.replyTo())
                ? message.replyTo() : config.getReplyToAddress());
        return record;
    }

    /**
     * Determine the {@link BodyMode} for a record being persisted. Honors a
     * caller-declared {@code bodyMode} when present (which is how the
     * template path propagates author intent — DERIVED vs AUTHORED). Falls
     * back to inferring from which body fields are populated, defaulting to
     * AUTHORED when both are present (the conservative assumption for direct
     * API callers — they're treating their plain content as canonical).
     */
    private BodyMode resolveBodyMode(ResolvedMail message) {
        if (message.bodyMode() != null) return message.bodyMode();
        boolean hasHtml = StringUtils.hasText(message.htmlBody());
        boolean hasText = StringUtils.hasText(message.textBody());
        if (hasHtml && hasText) return BodyMode.HTML_WITH_AUTHORED_PLAIN;
        if (hasHtml) return BodyMode.HTML;
        return BodyMode.PLAIN;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null ? null : List.copyOf(values);
    }

    private record ResolvedBody(String html, String text, BodyMode mode) {}

    private record ResolvedMail(List<String> to,
                                List<String> cc,
                                List<String> bcc,
                                String subject,
                                String textBody,
                                String htmlBody,
                                BodyMode bodyMode,
                                List<FileInfo> attachments,
                                Long serverConfigId,
                                String replyTo,
                                Boolean readReceiptRequested,
                                MailPriority priority) {}
}
