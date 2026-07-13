package io.softa.starter.flow.dto;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sender-facing view of a CC entry and its read acknowledgement state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowSentCcView")
public class FlowSentCcView {

    @Schema(description = "Runtime instance id")
    private String instanceId;

    @Schema(description = "Flow code")
    private String flowCode;

    @Schema(description = "Published flow revision")
    private Integer flowRevision;

    @Schema(description = "Approval node id")
    private String nodeId;

    @Schema(description = "Approval node label")
    private String nodeLabel;

    @Schema(description = "Approval cycle number")
    private Integer cycleNumber;

    @Schema(description = "Sender actor id")
    private String senderActorId;

    @Schema(description = "Recipient actor id")
    private String recipientActorId;

    @Schema(description = "CC comment when sent")
    private String sentComment;

    @Schema(description = "CC sent time")
    private LocalDateTime sentAt;

    @Schema(description = "Whether the recipient has acknowledged the CC as read")
    private Boolean read;

    @Schema(description = "Read acknowledgement time when available")
    private LocalDateTime readAt;

    @Schema(description = "Read acknowledgement comment when available")
    private String readComment;
}

