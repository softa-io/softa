package io.softa.starter.message.sms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.base.enums.Language;
import io.softa.starter.message.sms.entity.SmsTemplate;

/**
 * Light DTO for listing SMS templates.
 */
@Data
@Schema(name = "SmsTemplateSummaryDTO")
public class SmsTemplateSummaryDTO {

    public static SmsTemplateSummaryDTO from(SmsTemplate template) {
        SmsTemplateSummaryDTO dto = new SmsTemplateSummaryDTO();
        dto.setId(template.getId());
        dto.setCode(template.getCode());
        dto.setName(template.getName());
        dto.setDescription(template.getDescription());
        dto.setLanguage(template.getLanguage());
        String content = template.getContent();
        dto.setContentPreview(content != null && content.length() > 100
                ? content.substring(0, 100) : content);
        return dto;
    }

    @Schema(description = "Template ID")
    private Long id;

    @Schema(description = "Template code")
    private String code;

    @Schema(description = "Display name")
    private String name;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Language tag")
    private Language language;

    @Schema(description = "Content preview (first 100 characters)")
    private String contentPreview;
}
