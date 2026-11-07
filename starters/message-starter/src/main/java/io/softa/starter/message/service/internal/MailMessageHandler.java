package io.softa.starter.message.service.internal;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.impl.MailDeliveryProcessor;
import io.softa.starter.message.mail.support.MailAddresses;
import io.softa.starter.message.mail.support.MailBodies;
import io.softa.starter.message.shared.TenantScopes;

/**
 * Internal acceptance handler for one email message. Acceptance checks only what the caller got wrong
 * — a recipient, well-formed addresses, content or a template to render it from — and stores the
 * request as it was made in a PENDING {@link MailSendRecord}. The record joins the caller's
 * transaction; the delivery loop picks it up once that commits.
 * <p>
 * Everything that depends on configuration happens at the first delivery attempt, in
 * {@link MailDeliveryProcessor}: rendering the template, choosing the server, charging the monthly
 * quota. A request that fails there still has its record — FAILED with the reason — instead of an
 * exception thrown at the caller.
 * <p>
 * Nothing is written before validation passes, so a rejection leaves the caller's transaction
 * usable: {@code MessageServiceImpl} does not roll back on a {@link BusinessException}.
 */
@Component
@RequiredArgsConstructor
final class MailMessageHandler {

    private final MailSendRecordService recordService;

    private final MessageProperties messageProperties;

    Long send(SendMailDTO dto) {
        return insert(accept(dto));
    }

    /** Accept every message before writing any: one invalid message rejects the whole batch. */
    List<Long> sendBatch(List<SendMailDTO> batch) {
        List<MailSendRecord> records = batch.stream().map(this::accept).toList();
        return records.stream().map(this::insert).toList();
    }

    private Long insert(MailSendRecord record) {
        return TenantScopes.inRecordOwnerContext(record.getPlatformScope(), () -> recordService.createOne(record));
    }

    /** Validate one request and build its PENDING record, without writing anything. */
    MailSendRecord accept(SendMailDTO dto) {
        List<String> to = immutableCopy(dto.getTo());
        if (CollectionUtils.isEmpty(to)) {
            throw new BusinessException("Mail send rejected: at least one recipient is required");
        }
        MailAddresses.requireValidMailboxes("to", to);
        MailAddresses.requireValidMailboxes("cc", dto.getCc());
        MailAddresses.requireValidMailboxes("bcc", dto.getBcc());
        String replyTo = MailAddresses.normalizeAddressList(dto.getReplyTo());
        MailAddresses.requireValidAddressList("replyTo", replyTo);

        String html = StringUtils.hasText(dto.getHtmlBody()) ? dto.getHtmlBody() : null;
        String text = StringUtils.hasText(dto.getTextBody()) ? dto.getTextBody() : null;
        boolean hasBody = html != null || text != null;
        if (!hasBody && dto.getTemplateId() == null && !StringUtils.hasText(dto.getTemplateCode())) {
            throw new BusinessException(
                    "Mail send rejected: no content — provide htmlBody/textBody or a template to render one");
        }
        if (hasBody) {
            MailBodies.requireWithinLimit(html, text, messageProperties.getMail().getMaxBodyChars());
        }

        MailSendRecord record = new MailSendRecord();
        record.setPlatformScope(dto.getScope() == MessageScope.PLATFORM);
        // templateId addresses the exact row (editor test sends: what you preview is what goes out,
        // enabled or not) and wins over templateCode, which resolves in the scope's tier.
        record.setTemplateId(dto.getTemplateId());
        record.setTemplateCode(StringUtils.hasText(dto.getTemplateCode()) ? dto.getTemplateCode() : null);
        record.setTemplateVariables(CollectionUtils.isEmpty(dto.getTemplateVariables())
                ? null : JsonUtils.objectToJsonNode(dto.getTemplateVariables()));
        record.setTraceId(ContextHolder.getContext().getTraceId());
        record.setServerConfigId(dto.getServerConfigId());
        record.setToAddresses(to);
        record.setCcAddresses(immutableCopy(dto.getCc()));
        record.setBccAddresses(immutableCopy(dto.getBcc()));
        record.setSubject(StringUtils.hasText(dto.getSubject()) ? dto.getSubject() : null);
        record.setBodyHtml(html);
        record.setBodyText(text);
        record.setBodyMode(hasBody ? MailBodies.modeOf(dto.getBodyMode(), html, text) : null);
        record.setAttachments(CollectionUtils.isEmpty(dto.getAttachments()) ? null : dto.getAttachments());
        record.setReplyTo(StringUtils.hasText(replyTo) ? replyTo : null);
        record.setReadReceiptRequested(dto.getReadReceiptRequested());
        record.setPriority(dto.getPriority());
        record.setStatus(MailSendStatus.PENDING);
        record.setRetryCount(0);
        record.setVersion(0L);
        return record;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null ? null : List.copyOf(values);
    }
}
