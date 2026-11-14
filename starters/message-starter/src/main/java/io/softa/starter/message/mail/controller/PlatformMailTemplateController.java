package io.softa.starter.message.mail.controller;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.mail.dto.MailTemplatePreviewDTO;
import io.softa.starter.message.mail.dto.MailTemplateVariableDTO;
import io.softa.starter.message.mail.entity.PlatformMailTemplate;
import io.softa.starter.message.mail.service.PlatformMailTemplateService;

/**
 * REST controller for the platform's own email templates, on platform menus only: standard CRUD and
 * search from the generic model routes, plus the editor's preview and variable listing — the
 * platform-side twins of {@code /api/mail/templates/preview} and {@code /api/mail/templates/variables},
 * which read a tenant's templates. A test send of a platform template goes through
 * {@code /api/mail/send} with its {@code templateId} and {@code scope = PLATFORM}.
 */
@Tag(name = "PlatformMailTemplate")
@RestController
@RequestMapping("/PlatformMailTemplate")
public class PlatformMailTemplateController
        extends EntityController<PlatformMailTemplateService, PlatformMailTemplate, Long> {

    /**
     * The placeholder tokens of a platform template, for the Send Test / Preview inputs. Address by
     * {@code id} (the row being edited) or {@code code}; id wins.
     */
    @Operation(summary = "List placeholder variables of a platform mail template")
    @GetMapping("/variables")
    public ApiResponse<List<MailTemplateVariableDTO>> listVariables(
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) String code) {
        if (id != null) {
            return ApiResponse.success(service.listVariablesById(id));
        }
        if (!StringUtils.hasText(code)) {
            throw new BusinessException("Provide either a template id or a template code.");
        }
        return ApiResponse.success(service.listVariables(code));
    }

    /** Render a platform template with the given variables, without sending. Disabled ones too. */
    @Operation(summary = "Preview a rendered platform mail template")
    @PostMapping("/preview")
    public ApiResponse<MailTemplatePreviewDTO> preview(@RequestBody @Valid MailTemplatePreviewDTO request) {
        if (request.getId() == null && !StringUtils.hasText(request.getCode())) {
            throw new BusinessException("Provide either a template id or a template code.");
        }
        PlatformMailTemplate template = request.getId() != null
                ? service.getRequiredById(request.getId())
                : service.resolveAny(request.getCode());
        Map<String, Object> variables = request.getVariables() != null
                ? request.getVariables() : Collections.emptyMap();
        request.setRenderedSubject(service.renderSubject(template, variables));
        request.setRenderedBodyHtml(service.renderBodyHtml(template, variables));
        request.setRenderedBodyText(service.renderBodyText(template, variables));
        return ApiResponse.success(request);
    }
}
