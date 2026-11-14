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
 * read-only copy of the approval, in their CC list and inbox. Recipients are one group, named the way each group of an
 * approval step's {@code approverSources} is: specific people ({@code Users}), or a source that finds them — the
 * subject's supervisor, a role, a department's members… Several groups at one timing are several rules.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowCcRule")
public class FlowCcRule {

    @Schema(description = "When: OnSubmit, OnApproved or OnRejected")
    private CcTiming timing;

    @Schema(description = "Who is CC'd, one group in the shape of an approval step's approverSources entries: {\"type\": \"Users\", \"userIds\": […]} "
            + "for specific people, else a source found at the time ({\"type\": \"Supervisor|RoleQuery|Position|…\", …})")
    private Map<String, Object> recipientSource;
}
