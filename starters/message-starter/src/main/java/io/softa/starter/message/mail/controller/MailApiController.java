package io.softa.starter.message.mail.controller;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.mail.dto.*;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import io.softa.starter.message.mail.service.MailSendService;
import io.softa.starter.message.mail.service.MailTemplateService;

/**
 * External unified mail API for external systems and integrations.
 * <p>
 * Provides endpoints for:
 * <ul>
 *   <li>Sending emails (direct or template-based)</li>
 *   <li>Querying send status</li>
 *   <li>Listing available senders</li>
 *   <li>Listing and previewing templates</li>
 * </ul>
 */
@Tag(name = "MailApi")
@RestController
@RequestMapping("/api/mail")
public class MailApiController {

    @Autowired
    private MailSendService mailSendService;

    @Autowired
    private MailSendRecordService sendRecordService;

    @Autowired
    private MailSendServerConfigService sendConfigService;

    @Autowired
    private MailTemplateService templateService;

    // -------------------------------------------------
    // Send
    // -------------------------------------------------

    /**
     * Send an email with full control over all parameters.
     * <p>
     * Supports differentiated batch: set {@code items} for per-recipient content.
     *
     * @param dto the send request
     * @return success response
     */
    @Operation(summary = "Send an email synchronously")
    @PostMapping("/sendNow")
    public ApiResponse<Long> sendNow(@RequestBody @Valid SendMailDTO dto) {
        mailSendService.sendNow(dto);
        return ApiResponse.success(null);
    }

    /**
     * Send an email asynchronously. Returns immediately; delivery happens in the background.
     */
    @Operation(summary = "Send an email asynchronously")
    @PostMapping("/sendAsync")
    public ApiResponse<Void> sendAsync(@RequestBody @Valid SendMailDTO dto) {
        mailSendService.sendAsync(dto);
        return ApiResponse.success(null);
    }

    /**
     * Send an email using a pre-defined template.
     *
     * @param code      the template code (e.g. "USER_WELCOME")
     * @param to        recipient address(es), comma-separated
     * @param variables template placeholder variables
     */
    @Operation(summary = "Send an email using a template")
    @PostMapping("/sendByTemplate")
    public ApiResponse<Void> sendByTemplate(
            @RequestParam String code,
            @RequestParam List<String> to,
            @RequestBody(required = false) Map<String, Object> variables) {
        mailSendService.sendByTemplate(code, to,
                variables != null ? variables : Collections.emptyMap());
        return ApiResponse.success(null);
    }

    // -------------------------------------------------
    // Status
    // -------------------------------------------------

    /**
     * Query the send status of a mail record by its ID.
     */
    @Operation(summary = "Query send status by record ID")
    @GetMapping("/status")
    public ApiResponse<MailSendStatusDTO> getStatus(@RequestParam Long id) {
        return sendRecordService.getById(id)
                .map(MailSendStatusDTO::from)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    // -------------------------------------------------
    // Senders
    // -------------------------------------------------

    /**
     * List all available (enabled) sending server configs for the current tenant.
     */
    @Operation(summary = "List available mail senders")
    @GetMapping("/senders")
    public ApiResponse<List<MailSenderSummaryDTO>> listSenders() {
        FlexQuery flexQuery = new FlexQuery(
                new Filters().eq(MailSendServerConfig::getIsEnabled, true),
                Orders.ofAsc(MailSendServerConfig::getSortOrder));
        List<MailSendServerConfig> configs = sendConfigService.searchList(flexQuery);
        List<MailSenderSummaryDTO> summaries = configs.stream()
                .map(MailSenderSummaryDTO::from)
                .toList();
        return ApiResponse.success(summaries);
    }

    // -------------------------------------------------
    // Templates
    // -------------------------------------------------

    /**
     * List all available (enabled) mail templates for the current tenant.
     */
    @Operation(summary = "List available mail templates")
    @GetMapping("/templates")
    public ApiResponse<List<MailTemplateSummaryDTO>> listTemplates() {
        Filters filters = new Filters()
                .eq(MailTemplate::getIsEnabled, true);
        List<MailTemplate> templates = templateService.searchList(filters);
        List<MailTemplateSummaryDTO> summaries = templates.stream()
                .map(MailTemplateSummaryDTO::from)
                .toList();
        return ApiResponse.success(summaries);
    }

    /**
     * Preview a rendered mail template with given variables, without sending.
     */
    @Operation(summary = "Preview a rendered mail template")
    @PostMapping("/templates/preview")
    public ApiResponse<MailTemplatePreviewDTO> previewTemplate(
            @RequestBody @Valid MailTemplatePreviewDTO request) {
        MailTemplate template = templateService.resolve(request.getCode());
        Map<String, Object> variables = request.getVariables() != null
                ? request.getVariables() : Collections.emptyMap();

        request.setRenderedSubject(templateService.renderSubject(template, variables));
        request.setRenderedBody(templateService.renderBody(template, variables));
        return ApiResponse.success(request);
    }

}
