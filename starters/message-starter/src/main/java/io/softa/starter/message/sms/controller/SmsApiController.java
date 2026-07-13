package io.softa.starter.message.sms.controller;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.dto.SmsSendStatusDTO;
import io.softa.starter.message.sms.dto.SmsTemplateSummaryDTO;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.SmsTemplateService;

/**
 * External unified SMS API for external systems and integrations.
 * <p>
 * Provides endpoints for:
 * <ul>
 *   <li>Sending SMS (direct or template-based)</li>
 *   <li>Querying send status</li>
 *   <li>Listing available templates</li>
 * </ul>
 */
@Tag(name = "SmsApi")
@RestController
@RequestMapping("/api/sms")
public class SmsApiController {

    @Autowired
    private MessageService messageService;

    @Autowired
    private SmsSendRecordService sendRecordService;

    @Autowired
    private SmsTemplateService templateService;

    // -------------------------------------------------
    // Send
    // -------------------------------------------------

    /**
     * Send an SMS with full control over all parameters.
     *
     * @return the created {@code SmsSendRecord} id.
     */
    @Operation(summary = "Send an SMS")
    @PostMapping("/send")
    public ApiResponse<Long> send(@RequestBody @Valid SendSmsDTO dto) {
        return ApiResponse.success(messageService.sendSms(dto));
    }

    /** Submit independent SMS messages as one atomic batch. */
    @Operation(summary = "Send an SMS batch")
    @PostMapping("/sendBatch")
    public ApiResponse<List<Long>> sendBatch(
            @RequestBody List<@Valid SendSmsDTO> messages) {
        return ApiResponse.success(messageService.sendSmsBatch(messages));
    }

    // -------------------------------------------------
    // Status
    // -------------------------------------------------

    /**
     * Query the send status of an SMS record by its ID.
     */
    @Operation(summary = "Query SMS send status by record ID")
    @GetMapping("/status")
    public ApiResponse<SmsSendStatusDTO> getStatus(@RequestParam Long id) {
        return sendRecordService.getById(id)
                .map(SmsSendStatusDTO::from)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    // -------------------------------------------------
    // Templates
    // -------------------------------------------------

    /**
     * List all available (enabled) SMS templates for the current tenant.
     */
    @Operation(summary = "List available SMS templates")
    @GetMapping("/templates")
    public ApiResponse<List<SmsTemplateSummaryDTO>> listTemplates() {
        Filters filters = new Filters()
                .eq(SmsTemplate::getIsEnabled, true);
        List<SmsTemplate> templates = templateService.searchList(filters);
        List<SmsTemplateSummaryDTO> summaries = templates.stream()
                .map(SmsTemplateSummaryDTO::from)
                .toList();
        return ApiResponse.success(summaries);
    }

}
