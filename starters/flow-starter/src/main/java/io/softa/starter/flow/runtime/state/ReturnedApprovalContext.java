package io.softa.starter.flow.runtime.state;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.ApprovalReturnTarget;

/**
 * Snapshot of an approval that has been returned and can be resubmitted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "ReturnedApprovalContext")
public class ReturnedApprovalContext {

    @Schema(description = "Returned pending approval snapshot")
    private PendingApproval pendingApproval;

    @Schema(description = "Return target")
    private ApprovalReturnTarget target;

    @Schema(description = "Resolved target actor id")
    private String targetActorId;

    @Schema(description = "Actor who performed the return")
    private String actorId;

    @Schema(description = "Optional return comment")
    private String comment;

    @Schema(description = "Time when the approval was returned")
    private LocalDateTime returnedAt;
}

