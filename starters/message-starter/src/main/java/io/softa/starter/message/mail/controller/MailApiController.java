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
import io.softa.starter.message.mail.service.MailTemplateService;
import io.softa.starter.message.service.MessageService;

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
    private MessageService messageService;

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
     * @param dto the send request
     * @return the created {@code MailSendRecord} id.
     */
    @Operation(summary = "Send an email")
    @PostMapping("/send")
    public ApiResponse<Long> send(@RequestBody @Valid SendMailDTO dto) {
        return ApiResponse.success(messageService.sendMail(dto));
    }

    /** Submit independent emails as one atomic batch. */
    @Operation(summary = "Send an email batch")
    @PostMapping("/sendBatch")
    public ApiResponse<List<Long>> sendBatch(
            @RequestBody List<@Valid SendMailDTO> messages) {
        return ApiResponse.success(messageService.sendMailBatch(messages));
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
                Orders.ofAsc(MailSendServerConfig::getSequence));
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
        request.setRenderedBodyHtml(templateService.renderBodyHtml(template, variables));
        request.setRenderedBodyText(templateService.renderBodyText(template, variables));
        return ApiResponse.success(request);
    }

}
