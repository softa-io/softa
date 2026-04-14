package io.softa.starter.message.sms.service.impl;

import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.message.sms.dto.BatchSmsItemDTO;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.entity.SmsTemplateProviderBinding;
import io.softa.starter.message.sms.enums.SmsDeliveryStatus;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.message.SmsRetryMessage;
import io.softa.starter.message.sms.message.SmsRetryProducer;
import io.softa.starter.message.sms.message.SmsSendMessage;
import io.softa.starter.message.sms.message.SmsSendProducer;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.SmsSendService;
import io.softa.starter.message.sms.service.SmsTemplateProviderBindingService;
import io.softa.starter.message.sms.service.SmsTemplateService;
import io.softa.starter.message.sms.support.SmsAdapterFactory;
import io.softa.starter.message.sms.support.SmsFailoverExecutor;
import io.softa.starter.message.sms.support.SmsProviderDispatcher;
import io.softa.starter.message.sms.support.adapter.SmsProviderAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of {@link SmsSendService}.
 * <p>
 * Resolves the provider config via {@link SmsProviderDispatcher}, selects the adapter
 * via {@link SmsAdapterFactory}, sends the SMS, and persists a {@link SmsSendRecord}
 * for audit and retry tracking.
 * <p>
 * Supports three batch modes:
 * <ul>
 *   <li><b>Single:</b> {@code phoneNumber} + {@code content}</li>
 *   <li><b>Uniform batch:</b> {@code phoneNumbers} + shared {@code content}</li>
 *   <li><b>Differentiated batch:</b> {@code items} with per-recipient content/variables</li>
 * </ul>
 */
@Slf4j
@Service
public class SmsSendServiceImpl implements SmsSendService {

    @Autowired
    private SmsProviderDispatcher dispatcher;

    @Autowired
    private SmsAdapterFactory adapterFactory;

    @Autowired
    private SmsSendRecordService recordService;

    @Autowired
    private SmsTemplateService templateService;

    @Autowired
    private SmsTemplateProviderBindingService bindingService;

    @Autowired
    private SmsRetryProducer retryProducer;

    @Autowired
    private SmsSendProducer sendProducer;

    @Autowired
    private SmsFailoverExecutor failoverExecutor;

    @Override
    public void sendNow(String phoneNumber, String content) {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber(phoneNumber);
        dto.setContent(content);
        sendNow(dto);
    }

    @Override
    public void sendNow(SendSmsDTO dto) {
        SmsProviderConfig config = dto.getProviderConfigId() != null
                ? dispatcher.resolveProviderById(dto.getProviderConfigId())
                : dispatcher.resolveProvider();

        // Differentiated batch: per-recipient items with individual content/variables
        if (!CollectionUtils.isEmpty(dto.getItems())) {
            sendDifferentiatedBatch(dto, config);
            return;
        }

        // Uniform batch: same content to multiple phone numbers
        List<String> phoneNumbers = resolvePhoneNumbers(dto);
        for (String phoneNumber : phoneNumbers) {
            SmsSendRecord record = buildRecord(dto, config, phoneNumber, dto.getContent());
            recordService.createOne(record);
            executeSend(dto, config, record, phoneNumber);
        }
    }

    @Override
    public void sendByTemplate(String code, String phoneNumber, Map<String, Object> variables) {
        sendByTemplate(code, List.of(phoneNumber), variables);
    }

    @Override
    public void sendByTemplate(String code, List<String> phoneNumbers, Map<String, Object> variables) {
        SmsTemplate template = templateService.resolve(code);
        String content = templateService.renderContent(template, variables != null ? variables : Collections.emptyMap());

        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumbers(phoneNumbers);
        dto.setContent(content);
        dto.setTemplateCode(code);
        dto.setTemplateVariables(variables);

        // Resolve provider bindings for failover
        List<SmsTemplateProviderBinding> bindings = resolveBindings(template);

        if (!bindings.isEmpty()) {
            // Failover mode: iterate bindings in sortOrder until one succeeds
            for (String phoneNumber : phoneNumbers) {
                sendWithFailover(dto, bindings, phoneNumber, content);
            }
        } else {
            sendNow(dto);
        }
    }

    /**
     * Send asynchronously via Pulsar message queue when available;
     * otherwise fall back to {@code @Async} thread pool.
     */
    @Async
    @Override
    public CompletableFuture<Void> sendAsync(SendSmsDTO dto) {
        if (sendProducer.isAvailable()) {
            sendProducer.send(new SmsSendMessage(dto, ContextHolder.getContext()));
            log.debug("SMS send request published to Pulsar for async processing");
            return CompletableFuture.completedFuture(null);
        }

        // Fallback: execute synchronously on @Async thread pool
        log.debug("Pulsar SMS send topic not available, falling back to @Async thread pool");
        try {
            sendNow(dto);
        } catch (Exception e) {
            log.error("Async SMS send failed: {}", e.getMessage(), e);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void retrySend(Long sendRecordId) {
        SmsSendRecord record = recordService.getById(sendRecordId).orElse(null);
        if (record == null) {
            log.warn("retrySend: SmsSendRecord not found for id={}", sendRecordId);
            return;
        }
        if (record.getStatus() != SmsSendStatus.RETRY) {
            log.info("retrySend: Record id={} is no longer in RETRY status (current={}), skipping.",
                    sendRecordId, record.getStatus());
            return;
        }

        // If the record was sent via a template, re-resolve bindings for failover
        if (StringUtils.hasText(record.getTemplateCode())) {
            try {
                SmsTemplate template = templateService.resolve(record.getTemplateCode());
                List<SmsTemplateProviderBinding> bindings = resolveBindings(template);
                if (!bindings.isEmpty()) {
                    SendSmsDTO dto = rebuildDTO(record);
                    retrySendWithFailover(dto, bindings, record);
                    return;
                }
            } catch (Exception e) {
                log.warn("retrySend: Failed to resolve template '{}' for failover, "
                        + "falling back to original provider. Error: {}",
                        record.getTemplateCode(), e.getMessage());
            }
        }

        // Legacy retry: use the original provider
        SmsProviderConfig config = dispatcher.resolveProviderById(record.getProviderConfigId());
        SendSmsDTO dto = rebuildDTO(record);
        executeSend(dto, config, record, record.getPhoneNumber());
    }

    // -------------------------------------------------
    // Failover helpers
    // -------------------------------------------------

    /**
     * Resolve provider bindings for a template. Tries tenant-level first,
     * then falls back to platform-level (tenant_id = 0) bindings.
     *
     * @return ordered list of enabled bindings, or empty list if none configured
     */
    List<SmsTemplateProviderBinding> resolveBindings(SmsTemplate template) {
        if (template.getId() == null) {
            return List.of();
        }
        List<SmsTemplateProviderBinding> bindings = bindingService.findByTemplateId(template.getId());
        if (bindings.isEmpty()) {
            bindings = bindingService.findPlatformBindingsByTemplateId(template.getId());
        }
        return bindings;
    }

    /**
     * Send to a single phone number using the failover binding chain.
     */
    private void sendWithFailover(SendSmsDTO dto, List<SmsTemplateProviderBinding> bindings,
                                  String phoneNumber, String content) {
        SmsTemplateProviderBinding firstBinding = bindings.getFirst();
        SmsProviderConfig firstConfig = dispatcher.resolveProviderById(firstBinding.getProviderConfigId());

        SmsSendRecord record = buildRecord(dto, firstConfig, phoneNumber, content);
        recordService.createOne(record);

        SmsFailoverExecutor.FailoverResult result = failoverExecutor.execute(dto, bindings, record, phoneNumber);
        if (result.success()) {
            recordService.updateOne(record);
        } else if (result.lastConfig() != null) {
            handleSendFailure(record, result.lastConfig(), record.getErrorCode(), record.getErrorMessage());
            recordService.updateOne(record);
        } else {
            // All provider configs failed to resolve — mark FAILED immediately, no retry possible
            record.setStatus(SmsSendStatus.FAILED);
            recordService.updateOne(record);
        }
    }

    /**
     * Retry send with failover: re-iterates bindings starting from the beginning.
     */
    private void retrySendWithFailover(SendSmsDTO dto, List<SmsTemplateProviderBinding> bindings,
                                       SmsSendRecord record) {
        SmsFailoverExecutor.FailoverResult result = failoverExecutor.execute(dto, bindings, record, record.getPhoneNumber());
        if (result.success()) {
            recordService.updateOne(record);
        } else if (result.lastConfig() != null) {
            handleSendFailure(record, result.lastConfig(), record.getErrorCode(), record.getErrorMessage());
            recordService.updateOne(record);
        } else {
            record.setStatus(SmsSendStatus.FAILED);
            recordService.updateOne(record);
        }
    }

    /**
     * Handle differentiated batch: each item has its own content or template variables.
     * If an item has direct {@code content}, use it. Otherwise, render from the parent
     * DTO's {@code templateCode} with the item's {@code templateVariables}.
     */
    private void sendDifferentiatedBatch(SendSmsDTO dto, SmsProviderConfig config) {
        SmsTemplate template = null;
        List<SmsTemplateProviderBinding> bindings = List.of();

        String effectiveSignName = dto.getSignName();
        String effectiveExternalTemplateId = dto.getExternalTemplateId();
        Long effectiveProviderConfigId = dto.getProviderConfigId();

        if (StringUtils.hasText(dto.getTemplateCode())) {
            template = templateService.resolve(dto.getTemplateCode());
            bindings = resolveBindings(template);

        }

        for (BatchSmsItemDTO item : dto.getItems()) {
            String itemContent = resolveItemContent(item, template, dto);

            SendSmsDTO itemDto = new SendSmsDTO();
            itemDto.setPhoneNumber(item.getPhoneNumber());
            itemDto.setContent(itemContent);
            itemDto.setTemplateCode(dto.getTemplateCode());
            itemDto.setTemplateVariables(item.getTemplateVariables());
            itemDto.setSignName(effectiveSignName);
            itemDto.setExternalTemplateId(effectiveExternalTemplateId);
            itemDto.setProviderConfigId(effectiveProviderConfigId);

            if (!bindings.isEmpty()) {
                sendWithFailover(itemDto, bindings, item.getPhoneNumber(), itemContent);
            } else {
                SmsSendRecord record = buildRecord(itemDto, config, item.getPhoneNumber(), itemContent);
                recordService.createOne(record);
                executeSend(itemDto, config, record, item.getPhoneNumber());
            }
        }
    }

    /**
     * Resolve content for a single batch item:
     * <ol>
     *   <li>Item's own {@code content} (if set)</li>
     *   <li>Template rendered with item's {@code templateVariables}</li>
     *   <li>Parent DTO's {@code content} (fallback)</li>
     * </ol>
     */
    private String resolveItemContent(BatchSmsItemDTO item, SmsTemplate template, SendSmsDTO parentDto) {
        // 1. Explicit content on the item
        if (StringUtils.hasText(item.getContent())) {
            return item.getContent();
        }
        // 2. Render template with per-item variables
        if (template != null && item.getTemplateVariables() != null) {
            return templateService.renderContent(template, item.getTemplateVariables());
        }
        // 3. Fallback to parent DTO content
        return parentDto.getContent();
    }

    private void executeSend(SendSmsDTO dto, SmsProviderConfig config,
                             SmsSendRecord record, String phoneNumber) {
        try {
            SmsProviderAdapter adapter = adapterFactory.getAdapter(config.getProviderType());
            SmsSendResult sendResult = adapter.send(config, SmsAdapterRequest.from(dto, phoneNumber));

            if (sendResult.isSuccess()) {
                record.setStatus(SmsSendStatus.SENT);
                record.setSentAt(LocalDateTime.now());
                record.setProviderMessageId(sendResult.getProviderMessageId());
            } else {
                handleSendFailure(record, config,
                        sendResult.getErrorCode(),
                        sendResult.getErrorMessage() != null
                                ? sendResult.getErrorMessage()
                                : "Provider returned failure");
            }
        } catch (Exception e) {
            handleSendFailure(record, config, null, e.getMessage());
        } finally {
            recordService.updateOne(record);
        }
    }

    private void handleSendFailure(SmsSendRecord record, SmsProviderConfig config,
                                   String errorCode, String errorMessage) {
        int maxRetry = config.getMaxRetryCount() != null ? config.getMaxRetryCount() : 0;

        record.setErrorCode(errorCode);

        if (maxRetry > 0 && record.getRetryCount() < maxRetry && retryProducer.isAvailable()) {
            record.setStatus(SmsSendStatus.RETRY);
            record.setRetryCount(record.getRetryCount() + 1);
            record.setErrorMessage(errorMessage);

            int delay = config.getRetryIntervalSeconds() != null ? config.getRetryIntervalSeconds() : 60;
            retryProducer.sendDelayed(
                    new SmsRetryMessage(record.getId(), config.getId(), ContextHolder.getContext()),
                    delay);

            log.warn("SMS send failed, scheduled retry {}/{} in {}s for record id={}: [{}] {}",
                    record.getRetryCount(), maxRetry, delay, record.getId(), errorCode, errorMessage);
        } else {
            record.setStatus(SmsSendStatus.FAILED);
            record.setErrorMessage(errorMessage);
            log.error("SMS send failed (no more retries) for record id={}: [{}] {}",
                    record.getId(), errorCode, errorMessage);
        }
    }

    private SmsSendRecord buildRecord(SendSmsDTO dto, SmsProviderConfig config,
                                      String phoneNumber, String content) {
        String preview = content != null && content.length() > 200 ? content.substring(0, 200) : content;

        SmsSendRecord record = new SmsSendRecord();
        record.setProviderConfigId(config.getId());
        record.setProviderType(config.getProviderType());
        record.setPhoneNumber(phoneNumber);
        record.setTemplateCode(dto.getTemplateCode());
        record.setContent(content);
        record.setContentPreview(preview);
        record.setStatus(SmsSendStatus.PENDING);
        record.setRetryCount(0);
        record.setDeliveryStatus(SmsDeliveryStatus.UNKNOWN);
        return record;
    }

    private SendSmsDTO rebuildDTO(SmsSendRecord record) {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber(record.getPhoneNumber());
        dto.setContent(record.getContent());
        dto.setTemplateCode(record.getTemplateCode());
        dto.setProviderConfigId(record.getProviderConfigId());
        return dto;
    }

    private List<String> resolvePhoneNumbers(SendSmsDTO dto) {
        List<String> numbers = new ArrayList<>();
        if (StringUtils.hasText(dto.getPhoneNumber())) {
            numbers.add(dto.getPhoneNumber());
        }
        if (!CollectionUtils.isEmpty(dto.getPhoneNumbers())) {
            numbers.addAll(dto.getPhoneNumbers());
        }
        return numbers;
    }
}
