package io.softa.starter.message.mail.dto;

import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Summary DTO for available mail sending server configurations.
 * Used by external systems to discover which sender identities are available.
 */
@Data
@Schema(name = "MailSenderSummaryDTO")
public class MailSenderSummaryDTO {

    public static MailSenderSummaryDTO from(MailSendServerConfig config) {
        MailSenderSummaryDTO dto = new MailSenderSummaryDTO();
        dto.setId(config.getId());
        dto.setName(config.getName());
        dto.setFromAddress(config.getFromAddress());
        dto.setFromName(config.getFromName());
        dto.setIsDefault(config.getIsDefault());
        dto.setIsEnabled(config.getIsEnabled());
        return dto;
    }

    @Schema(description = "Server config ID")
    private Long id;

    @Schema(description = "Config name")
    private String name;

    @Schema(description = "From address")
    private String fromAddress;

    @Schema(description = "From display name")
    private String fromName;

    @Schema(description = "Whether this is the default config")
    private Boolean isDefault;

    @Schema(description = "Whether this config is enabled")
    private Boolean isEnabled;
}
