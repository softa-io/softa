package io.softa.starter.flow.design;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.CcTiming;

/**
 * An approval flow's CC rule: at its timing — the approval submitted, approved or rejected — its recipients get a
 * read-only copy of the approval, in their CC list and inbox. Recipients are named the way an approval step's
 * approvers are, in an {@code approverSource}'s shape: specific people ({@code Users}), or a source that finds them —
 * the subject's supervisor, a role, a department's members…
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowCcRule")
public class FlowCcRule {

    @Schema(description = "When: OnSubmit, OnApproved or OnRejected")
    private CcTiming timing;

    @Schema(description = "Who is CC'd, in an approval step's approverSource shape: {\"type\": \"Users\", \"userIds\": […]} "
            + "for specific people, else a source found at the time ({\"type\": \"Supervisor|RoleQuery|Position|…\", …})")
    private Map<String, Object> recipientSource;
}
