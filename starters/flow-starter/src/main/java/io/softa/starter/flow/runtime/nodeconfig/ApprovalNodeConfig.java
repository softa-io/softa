package io.softa.starter.flow.runtime.nodeconfig;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.*;

/**
 * Strongly-typed config for {@code FlowNodeType.APPROVAL} nodes.
 *
 * <h3>Changes from the previous version</h3>
 * <ul>
 *   <li>{@code formPermissions} is the canonical field-level permission source,
 *       compiled into the bundle at design time; runtime clients may still read
 *       them via {@code GET /flow/instances/{instanceId}/nodes/{nodeId}/formPermissions}.</li>
 *   <li>A step has no timeout of its own: its flow's {@code remindAfterHours} reminds its approvers, and nothing
 *       is decided for them. A {@code timeout} saved on a step before is ignored.</li>
 *   <li>Whether approvers may return the approval is the flow's {@code returnEnabled}, the same at every step: a
 *       return always goes to the initiator and ends the instance. A {@code returnEnabled} saved on a step before
 *       is ignored.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalNodeConfig {

    // ── Approver resolution ────────────────────────────────────────────────

    /**
     * Who approves the step: specific people ({@code {"type": "Users", "userIds": [...]}}), or a source that finds them
     * when the step is reached ({@code {"type": "Supervisor", "level": 1, ...}}, a role query, a position, …) — see
     * {@link ApproverSourceType}. Each type's fields sit beside its {@code type}; interpreted by
     * {@code ApproverResolutionService}. A step's named approvers were a list of their own ({@code approvers}) before;
     * a step saved with one is moved into a {@code Users} source by the release's migration.
     */
    private Map<String, Object> approverSource;

    /** Behaviour when the resolved approver list is empty. */
    @Builder.Default
    private EmptyApproverStrategy emptyApproverStrategy = EmptyApproverStrategy.ERROR;

    /**
     * Under {@code AssignToRole}: the roles, by id, whose holders the step goes to when it finds nobody — a tenant's
     * own roles, which have no code. With {@link #emptyApproverRoleCodes}, either or both.
     */
    private List<String> emptyApproverRoleIds;

    /**
     * Under {@code AssignToRole}: the roles, by code, whose holders the step goes to when it finds nobody — the
     * built-in roles, whose codes are the same in every tenant, so a flow seeded to many tenants names them so.
     */
    private List<String> emptyApproverRoleCodes;

    // ── Vote thresholds ────────────────────────────────────────────────────

    /**
     * How many approvals pass the step. A rejection has no threshold: one approver's rejection rejects the approval,
     * as in an all-approve step (会签) and an any-one step (或签) alike. A {@code rejectMode} saved on a step before is
     * ignored.
     */
    @Builder.Default
    private VoteThresholdMode approvalMode = VoteThresholdMode.ANY_ONE;

    /** Used when mode is {@code MIN_COUNT}. */
    private Integer minCount;
    /** Used when approval mode is {@code PERCENTAGE} (0–100). */
    private Integer percentage;

    // ── Form field permissions ─────────────────────────────────────────────

    /**
     * Field-level permissions for this approval step.
     * Key = field path; value = HIDDEN | READONLY | EDITABLE | REQUIRED.
     * On approve only EDITABLE / REQUIRED fields are written back to the business row: a field not
     * listed here is not writable, and a node without this map writes nothing.
     * Compiled into the bundle at design time; {@code FormPermissionService} and
     * {@code GET /flow/instances/{instanceId}/nodes/{nodeId}/formPermissions}
     * expose the same map at runtime for read-only clients.
     */
    private Map<String, FormFieldPermission> formPermissions;
}
