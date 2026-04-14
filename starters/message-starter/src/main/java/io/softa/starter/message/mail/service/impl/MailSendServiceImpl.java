package io.softa.starter.message.mail.service.impl;

import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.mail.dto.BatchMailItemDTO;
import io.softa.starter.message.mail.dto.MailAttachmentDTO;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.enums.MailPriority;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.message.MailRetryMessage;
import io.softa.starter.message.mail.message.MailRetryProducer;
import io.softa.starter.message.mail.message.MailSendMessage;
import io.softa.starter.message.mail.message.MailSendProducer;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.MailSendService;
import io.softa.starter.message.mail.service.MailTemplateService;
import io.softa.starter.message.mail.support.MailSenderFactory;
import io.softa.starter.message.mail.support.MailServerDispatcher;

/**
 * Implementation of {@link MailSendService}.
 * <p>
 * Resolves the server config via {@link MailServerDispatcher}, builds a MimeMessage,
 * sends it, and persists a {@link MailSendRecord} for audit and retry tracking.
 */
@Slf4j
@Service
public class MailSendServiceImpl implements MailSendService {

    @Autowired
    private MailServerDispatcher dispatcher;

    @Autowired
    private MailSenderFactory senderFactory;

    @Autowired
    private MailSendRecordService recordService;

    @Autowired
    private MailTemplateService templateService;

    @Autowired
    private MailRetryProducer retryProducer;

    @Autowired
    private MailSendProducer sendProducer;

    @Override
    public void sendText(String to, String subject, String body) {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of(to));
        dto.setSubject(subject);
        dto.setTextBody(body);
        sendNow(dto);
    }

    @Override
    public void sendText(List<String> to, String subject, String body) {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(to);
        dto.setSubject(subject);
        dto.setTextBody(body);
        sendNow(dto);
    }

    @Override
    public void sendHtml(String to, String subject, String htmlBody) {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(List.of(to));
        dto.setSubject(subject);
        dto.setHtmlBody(htmlBody);
        sendNow(dto);
    }

    @Override
    public void sendHtml(List<String> to, String subject, String htmlBody) {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(to);
        dto.setSubject(subject);
        dto.setHtmlBody(htmlBody);
        sendNow(dto);
    }

    @Override
    public void sendNow(SendMailDTO dto) {
        MailSendServerConfig config = dto.getServerConfigId() != null
                ? dispatcher.resolveSendById(dto.getServerConfigId())
                : dispatcher.resolveSend();

        // Differentiated batch: per-recipient items with individual content/variables
        if (!CollectionUtils.isEmpty(dto.getItems())) {
            sendDifferentiatedBatch(dto, config);
            return;
        }

        MailSendRecord record = buildRecord(dto, config);
        recordService.createOne(record);

        executeSend(dto, config, record, null);
    }

    @Override
    public void sendByTemplate(String code, String to, Map<String, Object> variables) {
        sendByTemplate(code, List.of(to), variables);
    }

    @Override
    public void sendByTemplate(String code, List<String> to, Map<String, Object> variables) {
        MailTemplate template = templateService.resolve(code);
        String htmlBody = templateService.renderBody(template, variables);

        SendMailDTO dto = new SendMailDTO();
        dto.setTo(to);
        dto.setSubject(templateService.renderSubject(template, variables));
        dto.setHtmlBody(htmlBody);
        if (Boolean.TRUE.equals(template.getIncludePlainText())) {
            dto.setTextBody(Jsoup.parse(htmlBody).text());
        }

        // Apply template-level default priority if DTO does not override
        if (dto.getPriority() == null && StringUtils.hasText(template.getDefaultPriority())) {
            try {
                dto.setPriority(MailPriority.valueOf(template.getDefaultPriority()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid defaultPriority '{}' on template '{}', ignoring.",
                        template.getDefaultPriority(), code);
            }
        }

        sendNow(dto);
    }

    /**
     * Send asynchronously via Pulsar message queue when available;
     * otherwise fall back to {@code @Async} thread pool.
     */
    @Async
    @Override
    public CompletableFuture<Void> sendAsync(SendMailDTO dto) {
        if (sendProducer.isAvailable()) {
            sendProducer.send(new MailSendMessage(dto, ContextHolder.getContext()));
            log.debug("Mail send request published to Pulsar for async processing");
            return CompletableFuture.completedFuture(null);
        }

        log.debug("Pulsar mail send topic not available, falling back to @Async thread pool");
        try {
            sendNow(dto);
        } catch (Exception e) {
            log.error("Async email send failed: {}", e.getMessage(), e);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void retrySend(Long sendRecordId) {
        MailSendRecord record = recordService.getById(sendRecordId).orElse(null);
        if (record == null) {
            log.warn("retrySend: MailSendRecord not found for id={}", sendRecordId);
            return;
        }
        if (record.getStatus() != MailSendStatus.RETRY) {
            log.info("retrySend: Record id={} is no longer in RETRY status (current={}), skipping.",
                    sendRecordId, record.getStatus());
            return;
        }

        MailSendServerConfig config = dispatcher.resolveSendById(record.getServerConfigId());
        SendMailDTO dto = rebuildDTO(record);
        executeSend(dto, config, record, null);
    }

    // -------------------------------------------------
    // Internal helpers
    // -------------------------------------------------

    /**
     * Handle differentiated batch: each item has its own recipients, subject, body
     * or template variables. One email per item is sent independently.
     */
    private void sendDifferentiatedBatch(SendMailDTO parentDto, MailSendServerConfig config) {
        MailTemplate template = null;
        if (StringUtils.hasText(parentDto.getTemplateCode())) {
            template = templateService.resolve(parentDto.getTemplateCode());
        }

        for (BatchMailItemDTO item : parentDto.getItems()) {
            SendMailDTO itemDto = buildItemDto(item, template, parentDto);

            MailSendRecord record = buildRecord(itemDto, config);
            recordService.createOne(record);
            executeSend(itemDto, config, record, template);
        }
    }

    /**
     * Build a per-item {@link SendMailDTO} from a {@link BatchMailItemDTO}.
     * <p>
     * Resolution priority for each field:
     * <ol>
     *   <li>Item's own value (if set)</li>
     *   <li>Template rendered with item's templateVariables</li>
     *   <li>Parent DTO's value (fallback)</li>
     * </ol>
     */
    private SendMailDTO buildItemDto(BatchMailItemDTO item, MailTemplate template, SendMailDTO parentDto) {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(item.getTo());
        dto.setCc(item.getCc() != null ? item.getCc() : parentDto.getCc());
        dto.setBcc(item.getBcc() != null ? item.getBcc() : parentDto.getBcc());
        dto.setServerConfigId(parentDto.getServerConfigId());
        dto.setReplyTo(parentDto.getReplyTo());
        dto.setReadReceiptRequested(parentDto.getReadReceiptRequested());
        dto.setPriority(parentDto.getPriority());
        dto.setAttachments(parentDto.getAttachments());

        // Resolve subject
        dto.setSubject(resolveItemSubject(item, template, parentDto));

        // Resolve body
        resolveItemBody(item, template, parentDto, dto);

        return dto;
    }

    private String resolveItemSubject(BatchMailItemDTO item, MailTemplate template, SendMailDTO parentDto) {
        // 1. Explicit subject on the item
        if (StringUtils.hasText(item.getSubject())) {
            return item.getSubject();
        }
        // 2. Render template subject with per-item variables
        if (template != null && item.getTemplateVariables() != null) {
            return templateService.renderSubject(template, item.getTemplateVariables());
        }
        // 3. Fallback to parent DTO subject
        return parentDto.getSubject();
    }

    private void resolveItemBody(BatchMailItemDTO item, MailTemplate template,
                                 SendMailDTO parentDto, SendMailDTO itemDto) {
        // 1. Explicit body on the item
        if (StringUtils.hasText(item.getHtmlBody()) || StringUtils.hasText(item.getTextBody())) {
            itemDto.setHtmlBody(item.getHtmlBody());
            itemDto.setTextBody(item.getTextBody());
            return;
        }
        // 2. Render template with per-item variables
        if (template != null && item.getTemplateVariables() != null) {
            String htmlBody = templateService.renderBody(template, item.getTemplateVariables());
            itemDto.setHtmlBody(htmlBody);
            if (Boolean.TRUE.equals(template.getIncludePlainText())) {
                itemDto.setTextBody(Jsoup.parse(htmlBody).text());
            }
            return;
        }
        // 3. Fallback to parent body
        itemDto.setHtmlBody(parentDto.getHtmlBody());
        itemDto.setTextBody(parentDto.getTextBody());
    }

    /**
     * Core send execution with retry support.
     *
     * @param dto      the send request
     * @param config   resolved server config
     * @param record   the persisted audit record (will be updated in-place)
     * @param template optional template for priority fallback (may be null)
     */
    private void executeSend(SendMailDTO dto, MailSendServerConfig config,
                             MailSendRecord record, MailTemplate template) {
        try {
            doSend(dto, config, template);
            record.setStatus(MailSendStatus.SENT);
            record.setSentAt(LocalDateTime.now());
        } catch (Exception e) {
            handleSendFailure(record, config, e);
        } finally {
            recordService.updateOne(record);
        }
    }

    private void handleSendFailure(MailSendRecord record, MailSendServerConfig config, Exception e) {
        int maxRetry = config.getMaxRetryCount() != null ? config.getMaxRetryCount() : 0;

        if (maxRetry > 0 && record.getRetryCount() < maxRetry && retryProducer.isAvailable()) {
            record.setStatus(MailSendStatus.RETRY);
            record.setRetryCount(record.getRetryCount() + 1);
            record.setErrorMessage(e.getMessage());

            int delay = config.getRetryIntervalSeconds() != null ? config.getRetryIntervalSeconds() : 60;
            retryProducer.sendDelayed(
                    new MailRetryMessage(record.getId(), config.getId(), ContextHolder.getContext()),
                    delay);

            log.warn("Email send failed, scheduled retry {}/{} in {}s for record id={}: {}",
                    record.getRetryCount(), maxRetry, delay, record.getId(), e.getMessage());
        } else {
            record.setStatus(MailSendStatus.FAILED);
            record.setErrorMessage(e.getMessage());
            log.error("Email send failed (no more retries) for record id={}: {}",
                    record.getId(), e.getMessage(), e);
        }
    }

    private void doSend(SendMailDTO dto, MailSendServerConfig config, MailTemplate template)
            throws MessagingException, UnsupportedEncodingException {
        JavaMailSenderImpl sender = senderFactory.getSender(config);
        MimeMessage message = sender.createMimeMessage();
        boolean hasAttachments = !CollectionUtils.isEmpty(dto.getAttachments());
        boolean multipart = hasAttachments
                || (StringUtils.hasText(dto.getHtmlBody()) && StringUtils.hasText(dto.getTextBody()));
        MimeMessageHelper helper = new MimeMessageHelper(message, multipart, "UTF-8");

        // --- From / To / CC / BCC / Reply-To ---
        String fromAddress = StringUtils.hasText(config.getFromAddress())
                ? config.getFromAddress() : config.getUsername();
        String fromName = StringUtils.hasText(config.getFromName()) ? config.getFromName() : fromAddress;
        helper.setFrom(fromAddress, fromName);

        helper.setTo(dto.getTo().toArray(new String[0]));
        if (!CollectionUtils.isEmpty(dto.getCc())) {
            helper.setCc(dto.getCc().toArray(new String[0]));
        }
        if (!CollectionUtils.isEmpty(dto.getBcc())) {
            helper.setBcc(dto.getBcc().toArray(new String[0]));
        }

        String replyTo = StringUtils.hasText(dto.getReplyTo())
                ? dto.getReplyTo() : config.getReplyToAddress();
        if (StringUtils.hasText(replyTo)) {
            helper.setReplyTo(replyTo);
        }

        helper.setSubject(dto.getSubject());

        // --- Read Receipt headers (S1) ---
        boolean requestReceipt = Boolean.TRUE.equals(
                dto.getReadReceiptRequested() != null
                        ? dto.getReadReceiptRequested()
                        : config.getReadReceiptEnabled());
        if (requestReceipt) {
            message.setHeader("Disposition-Notification-To", fromAddress);   // RFC 8098 standard
            message.setHeader("Return-Receipt-To", fromAddress);             // Legacy compatibility
        }

        // --- Priority headers (S2) ---
        MailPriority priority = dto.getPriority();
        if (priority == null && template != null && StringUtils.hasText(template.getDefaultPriority())) {
            try {
                priority = MailPriority.valueOf(template.getDefaultPriority());
            } catch (IllegalArgumentException ignored) {
                // invalid template priority, skip
            }
        }
        if (priority != null) {
            message.setHeader("X-Priority", priority.getXPriority());
            message.setHeader("Importance", priority.getImportance());           // RFC 2156
            message.setHeader("X-MSMail-Priority", priority.getXMsMailPriority());
        }

        // --- Body ---
        boolean hasHtml = StringUtils.hasText(dto.getHtmlBody());
        boolean hasText = StringUtils.hasText(dto.getTextBody());
        if (hasHtml && hasText) {
            helper.setText(dto.getTextBody(), dto.getHtmlBody());
        } else if (hasHtml) {
            helper.setText(dto.getHtmlBody(), true);
        } else {
            helper.setText(dto.getTextBody(), false);
        }

        // --- Attachments ---
        if (hasAttachments) {
            for (MailAttachmentDTO attachment : dto.getAttachments()) {
                if (attachment.getData() != null) {
                    helper.addAttachment(attachment.getFileName(),
                            () -> new java.io.ByteArrayInputStream(attachment.getData()),
                            attachment.getContentType());
                }
            }
        }

        sender.send(message);
    }

    private MailSendRecord buildRecord(SendMailDTO dto, MailSendServerConfig config) {
        boolean isHtml = StringUtils.hasText(dto.getHtmlBody());
        String body = isHtml ? dto.getHtmlBody() : dto.getTextBody();
        String preview = body != null && body.length() > 500 ? body.substring(0, 500) : body;

        MailSendRecord record = new MailSendRecord();
        record.setServerConfigId(config.getId());
        record.setFromAddress(config.getFromAddress());
        record.setToAddresses(toJson(dto.getTo()));
        record.setCcAddresses(toJson(dto.getCc()));
        record.setBccAddresses(toJson(dto.getBcc()));
        record.setSubject(dto.getSubject());
        record.setContentType(isHtml ? "HTML" : "TEXT");
        record.setBodyPreview(preview);
        record.setBody(body);
        record.setHasAttachments(!CollectionUtils.isEmpty(dto.getAttachments()));
        record.setStatus(MailSendStatus.PENDING);
        record.setRetryCount(0);

        // Phase-1 fields
        boolean requestReceipt = Boolean.TRUE.equals(
                dto.getReadReceiptRequested() != null
                        ? dto.getReadReceiptRequested()
                        : config.getReadReceiptEnabled());
        record.setReadReceiptRequested(requestReceipt);

        if (dto.getPriority() != null) {
            record.setPriority(dto.getPriority().getCode());
        }

        return record;
    }

    /**
     * Rebuild a {@link SendMailDTO} from a persisted {@link MailSendRecord} for retry.
     */
    private SendMailDTO rebuildDTO(MailSendRecord record) {
        SendMailDTO dto = new SendMailDTO();
        dto.setTo(fromJson(record.getToAddresses()));
        dto.setCc(fromJson(record.getCcAddresses()));
        dto.setBcc(fromJson(record.getBccAddresses()));
        dto.setSubject(record.getSubject());
        dto.setServerConfigId(record.getServerConfigId());

        String bodyContent = StringUtils.hasText(record.getBody())
                ? record.getBody() : record.getBodyPreview();
        if ("HTML".equals(record.getContentType())) {
            dto.setHtmlBody(bodyContent);
        } else {
            dto.setTextBody(bodyContent);
        }

        if (Boolean.TRUE.equals(record.getReadReceiptRequested())) {
            dto.setReadReceiptRequested(true);
        }
        if (StringUtils.hasText(record.getPriority())) {
            try {
                dto.setPriority(MailPriority.valueOf(record.getPriority()));
            } catch (IllegalArgumentException ignored) {
                // skip invalid
            }
        }

        return dto;
    }

    private String toJson(List<String> list) {
        return CollectionUtils.isEmpty(list) ? null : JsonUtils.objectToString(list);
    }

    private List<String> fromJson(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        List<String> result = JsonUtils.stringToObject(json, new TypeReference<>() {});
        return result != null ? result : List.of();
    }
}
