package io.softa.starter.flow.runtime.nodeconfig;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
     * Who approves the step, in one or more groups: each group specific people ({@code {"type": "Users", "userIds":
     * [...]}}), or a source that finds them when the step is reached ({@code {"type": "Supervisor", "level": 1, ...}},
     * a role query, a position, …) — see {@link ApproverSourceType}. Each type's fields sit beside its {@code type};
     * interpreted by {@code ApproverResolutionService}.
     * <p>
     * The groups make one pool: the step's approvers are everyone its groups find, each once, and the approval mode
     * counts over that pool — any one of them, all of them, so many. A group that finds nobody leaves the others to
     * decide; the empty-approver strategy applies when the pool is empty. Parties that must each decide — in an
     * order, on fields of their own, or stopping the approval when one of them is missing — are steps of their own.
     * <p>
     * A step had one source ({@code approverSource}) and its named approvers in a list of their own ({@code approvers})
     * before; the release's migration moves the one it used into this list.
     */
    private List<Map<String, Object>> approverSources;

    /**
     * Behaviour when the resolved approver list is empty — applied only to a step that finds its approvers by rule
     * ({@link #findsApproversByRule}): one that only names them stops and says why when none of them can act, whatever
     * this holds.
     */
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
     * How many approvals pass the step — applied only to a step that may have several approvers
     * ({@link #mayHaveSeveralApprovers}): one with one approver at most passes on that one's approval, whatever this
     * holds. A rejection has no threshold: one approver's rejection rejects the approval, as in an all-approve step
     * (会签) and an any-one step (或签) alike. A {@code rejectMode} saved on a step before is ignored.
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

    // ── What applies ───────────────────────────────────────────────────────
    // Not bean properties (no get / is prefix), so they are never read or written as config keys.

    /**
     * Whether the step may have several approvers, so its approval mode applies: several groups, or one that names
     * several people or may find several. A step whose one group names one person, or finds one at most (a supervisor,
     * a department's leader or HR business partner — {@link ApproverSourceType#isFindsOne}), has one approver at most;
     * the designer does not offer the mode then.
     */
    public boolean mayHaveSeveralApprovers() {
        List<Map<String, Object>> groups = groups();
        if (groups.size() != 1) {
            return groups.size() > 1;
        }
        Map<String, Object> group = groups.getFirst();
        if (ApproverSourceType.USERS.getType().equals(group.get("type"))) {
            return ApproverSourceType.namedUsers(group).stream().distinct().count() > 1;
        }
        ApproverSourceType source = ApproverSourceType.lookup(group.get("type"));
        return source == null || !source.isFindsOne();
    }

    /**
     * Whether a group of the step finds its people when the step is reached, so its empty-approver strategy applies. A
     * step that only names its people has nobody only when none of them can act any more — its flow needs changing —
     * and stops and says why; the designer does not offer the strategy then.
     */
    public boolean findsApproversByRule() {
        return groups().stream().map(group -> group.get("type"))
                .anyMatch(type -> type != null && !type.toString().isBlank()
                        && !ApproverSourceType.USERS.getType().equals(type));
    }

    private List<Map<String, Object>> groups() {
        return approverSources == null ? List.of() : approverSources.stream().filter(Objects::nonNull).toList();
    }
}
