package io.softa.starter.message.sms.controller;

import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.SmsSendService;
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
    private SmsSendService smsSendService;

    @Autowired
    private SmsSendRecordService sendRecordService;

    @Autowired
    private SmsTemplateService templateService;

    // -------------------------------------------------
    // Send
    // -------------------------------------------------

    /**
     * Send an SMS with full control over all parameters.
     */
    @Operation(summary = "Send an SMS synchronously")
    @PostMapping("/sendNow")
    public ApiResponse<Void> sendNow(@RequestBody @Valid SendSmsDTO dto) {
        smsSendService.sendNow(dto);
        return ApiResponse.success(null);
    }

    /**
     * Send an SMS asynchronously via message queue.
     * <p>
     * The request is published to the Pulsar SMS-send topic for background processing.
     * If Pulsar is not available, it falls back to an {@code @Async} thread pool.
     */
    @Operation(summary = "Send an SMS asynchronously (via message queue)")
    @PostMapping("/sendAsync")
    public ApiResponse<Void> sendAsync(@RequestBody @Valid SendSmsDTO dto) {
        smsSendService.sendAsync(dto);
        return ApiResponse.success(null);
    }

    /**
     * Send an SMS using a pre-defined template.
     *
     * @param code         the template code (e.g. "VERIFY_CODE")
     * @param phoneNumbers recipient phone number(s)
     * @param variables    template placeholder variables
     */
    @Operation(summary = "Send an SMS using a template")
    @PostMapping("/sendByTemplate")
    public ApiResponse<Void> sendByTemplate(
            @RequestParam String code,
            @RequestParam List<String> phoneNumbers,
            @RequestBody(required = false) Map<String, Object> variables) {
        smsSendService.sendByTemplate(code, phoneNumbers,
                variables != null ? variables : Collections.emptyMap());
        return ApiResponse.success(null);
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
