package io.softa.starter.message.inbox.dto;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import io.softa.starter.message.inbox.enums.NotificationType;

/** One in-app notification for one recipient. */
@Data
@Schema(name = "SendInboxDTO")
public class SendInboxDTO {

    @NotNull
    @Schema(description = "Recipient user ID")
    private Long recipientId;

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    private String content;

    @Schema(description = "Notification type; defaults to SYSTEM")
    private NotificationType notificationType;

    @Size(max = 50)
    @Schema(description = "Optional source metadata model name")
    private String sourceModel;

    @Schema(description = "Optional source object ID")
    private Long sourceId;

    @Schema(description = "Optional expiry time")
    private LocalDateTime expiredAt;
}
