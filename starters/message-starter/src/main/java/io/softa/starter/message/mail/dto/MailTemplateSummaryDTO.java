package io.softa.starter.message.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.base.enums.Language;
import io.softa.starter.message.mail.entity.MailTemplate;

/**
 * Summary DTO for available mail templates.
 * Used by external systems to discover which templates can be used for sending.
 */
@Data
@Schema(name = "MailTemplateSummaryDTO")
public class MailTemplateSummaryDTO {

    public static MailTemplateSummaryDTO from(MailTemplate template) {
        MailTemplateSummaryDTO dto = new MailTemplateSummaryDTO();
        dto.setId(template.getId());
        dto.setCode(template.getCode());
        dto.setName(template.getName());
        dto.setDescription(template.getDescription());
        dto.setLanguage(template.getLanguage());
        dto.setSubject(template.getSubject());
        dto.setDefaultPriority(template.getDefaultPriority());
        return dto;
    }

    @Schema(description = "Template ID")
    private Long id;

    @Schema(description = "Unique template code, e.g. USER_WELCOME")
    private String code;

    @Schema(description = "Display name")
    private String name;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Language tag (e.g. en-US, zh-CN, default)")
    private Language language;

    @Schema(description = "Subject template (may contain {{ variable }} placeholders)")
    private String subject;

    @Schema(description = "Default priority: High / Normal / Low")
    private String defaultPriority;
}
