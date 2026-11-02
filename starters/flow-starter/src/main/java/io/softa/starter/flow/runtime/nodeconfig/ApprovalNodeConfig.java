package io.softa.starter.flow.runtime.nodeconfig;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.*;
import io.softa.starter.flow.runtime.spi.ApprovalTimeoutConfig;

/**
 * Strongly-typed config for {@code FlowNodeType.APPROVAL} nodes.
 *
 * <h3>Changes from the previous version</h3>
 * <ul>
 *   <li>{@code formPermissions} is the canonical field-level permission source,
 *       compiled into the bundle at design time; runtime clients may still read
 *       them via {@code GET /flow/instances/{instanceId}/nodes/{nodeId}/formPermissions}.</li>
 *   <li>{@code config.timeout.timeoutStrategy} ({@link ApprovalTimeoutStrategy}:
 *       REMIND, AUTO_APPROVE, AUTO_REJECT, ESCALATE) replaces the former loose
 *       timeout fields on {@code FlowGraphNode}.</li>
 *   <li>A return always goes to the initiator and ends the instance; there is no
 *       return target to configure.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalNodeConfig {

    // ── Approver resolution ────────────────────────────────────────────────

    /** Static list of approver actor ids. */
    private List<String> approvers;

    /**
     * Dynamic approver source descriptor (mutually exclusive with {@code approvers}).
     * Interpreted by {@code ApproverResolutionService}.
     * Structure: {@code { "type": "Role|VariableList|Expression|InitiatorManager", ...fields }}
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

    @Builder.Default
    private VoteThresholdMode approvalMode = VoteThresholdMode.ANY_ONE;
    @Builder.Default
    private VoteThresholdMode rejectMode   = VoteThresholdMode.ANY_ONE;

    /** Used when mode is {@code MIN_COUNT}. */
    private Integer minCount;
    /** Used when approval mode is {@code PERCENTAGE} (0–100). */
    private Integer percentage;
    /** Used when reject mode is {@code MIN_COUNT}. */
    private Integer rejectMinCount;
    /** Used when reject mode is {@code PERCENTAGE} (0–100). */
    private Integer rejectPercentage;

    // ── Timeout ────────────────────────────────────────────────────────────

    /**
     * Timeout configuration ({@link ApprovalTimeoutConfig#getTimeoutStrategy()}).
     * {@link ApprovalTimeoutStrategy#ESCALATE} requires {@code escalateToUserId}.
     */
    private ApprovalTimeoutConfig timeout;

    // ── Return policy ──────────────────────────────────────────────────────

    /**
     * Whether the approver may return the approval to the initiator, which ends the instance as
     * {@code RETURNED}; the initiator then edits and submits again, starting a new instance.
     */
    private Boolean returnEnabled;

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
