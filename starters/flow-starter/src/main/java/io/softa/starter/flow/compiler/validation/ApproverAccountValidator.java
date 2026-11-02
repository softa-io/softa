package io.softa.starter.flow.compiler.validation;

import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowCcRule;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.enums.ApprovalTimeoutStrategy;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.engine.ActorAccounts;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.spi.ApprovalTimeoutConfig;

/**
 * Warns of the users a flow names who cannot act now ({@link ActorAccounts}) — an approval step's static approvers,
 * the user its timeout escalates to, a CC rule's named recipients. A warning, never an error: an account changes
 * after the flow is published, and the engine already passes over whoever cannot act when the step is reached (the
 * step then follows its empty-approver strategy, an escalation reminds instead, the CC skips them). A
 * {@link FlowValidator} bean, so the editor's validation and every publish run it; a compiler built without Spring
 * does not.
 */
@Slf4j
@Component
public class ApproverAccountValidator implements FlowValidator {

    private static final String APPROVAL_TYPE = FlowNodeType.APPROVAL.getType();

    private static final String UNUSABLE_APPROVER = "UNUSABLE_APPROVER";
    private static final String UNUSABLE_ESCALATE_TARGET = "UNUSABLE_ESCALATE_TARGET";
    private static final String UNUSABLE_CC_RECIPIENT = "UNUSABLE_CC_RECIPIENT";

    private final ActorAccounts actorAccounts;

    public ApproverAccountValidator(ActorAccounts actorAccounts) {
        this.actorAccounts = actorAccounts;
    }

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        Map<String, ApprovalNodeConfig> steps = new LinkedHashMap<>();
        Set<String> named = new LinkedHashSet<>();
        for (FlowGraphNode node : context.nodeMap().values()) {
            ApprovalNodeConfig config = approvalConfig(node);
            if (config == null) {
                continue;
            }
            steps.put(node.getId(), config);
            named.addAll(approvers(config));
            String escalateTo = escalateTarget(config);
            if (escalateTo != null) {
                named.add(escalateTo);
            }
        }
        List<FlowCcRule> rules = definition == null || definition.getCc() == null ? List.of() : definition.getCc();
        rules.forEach(rule -> named.addAll(recipients(rule)));
        if (named.isEmpty()) {
            return List.of();
        }
        Map<String, String> unusable;
        try {
            // One lookup for the whole flow.
            unusable = actorAccounts.whyUnusable(named);
        } catch (RuntimeException e) {
            // Advice only: a lookup that fails must not keep the flow from validating or publishing.
            log.warn("Could not check the accounts flow '{}' names: {}", definition == null ? null : definition.getName(),
                    e.getMessage(), e);
            return List.of();
        }
        if (unusable.isEmpty()) {
            return List.of();
        }

        List<CompileDiagnostic> d = new ArrayList<>();
        steps.forEach((nodeId, config) -> {
            String approvers = reasons(approvers(config), unusable);
            if (approvers != null) {
                d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.approvers", UNUSABLE_APPROVER,
                        "Approval node '" + nodeId + "' names approvers who cannot act now and are passed over when "
                                + "the step is reached: " + approvers).asWarning());
            }
            String escalateTo = escalateTarget(config);
            String target = escalateTo == null ? null : reasons(List.of(escalateTo), unusable);
            if (target != null) {
                d.add(CompileDiagnostic.fieldLevel(nodeId, APPROVAL_TYPE, "config.timeout.escalateToUserId",
                        UNUSABLE_ESCALATE_TARGET, "Approval node '" + nodeId + "' escalates to someone who cannot act "
                                + "now, so an overdue step is reminded instead: " + target).asWarning());
            }
        });
        for (int i = 0; i < rules.size(); i++) {
            String recipients = reasons(recipients(rules.get(i)), unusable);
            if (recipients != null) {
                d.add(CompileDiagnostic.flowLevel(UNUSABLE_CC_RECIPIENT, "CC rule " + (i + 1) + " names recipients "
                        + "who cannot act now and are not CC'd: " + recipients).asWarning());
            }
        }
        return d;
    }

    /** The node's approval config; null for another node, or one whose config cannot be read (reported elsewhere). */
    private static ApprovalNodeConfig approvalConfig(FlowGraphNode node) {
        if (!FlowNodeType.APPROVAL.equals(node.getType()) || node.getConfig() == null) {
            return null;
        }
        try {
            return BeanTool.objectToObject(node.getConfig(), ApprovalNodeConfig.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> approvers(ApprovalNodeConfig config) {
        return config.getApprovers() == null ? List.of()
                : config.getApprovers().stream().filter(StringUtils::hasText).map(String::trim).toList();
    }

    /** The user an Escalate timeout hands the step to; null when the step does not escalate. */
    private static String escalateTarget(ApprovalNodeConfig config) {
        ApprovalTimeoutConfig timeout = config.getTimeout();
        return timeout != null && ApprovalTimeoutStrategy.ESCALATE.equals(timeout.getTimeoutStrategy())
                && StringUtils.hasText(timeout.getEscalateToUserId()) ? timeout.getEscalateToUserId().trim() : null;
    }

    private static List<String> recipients(FlowCcRule rule) {
        return rule == null || rule.getRecipients() == null ? List.of()
                : rule.getRecipients().stream().filter(StringUtils::hasText).map(String::trim).toList();
    }

    /** Why those of {@code userIds} who cannot act cannot, joined; null when they all can. */
    private static String reasons(List<String> userIds, Map<String, String> unusable) {
        StringJoiner reasons = new StringJoiner("; ").setEmptyValue("");
        userIds.stream().distinct().filter(unusable::containsKey).forEach(userId -> reasons.add(unusable.get(userId)));
        return reasons.length() == 0 ? null : reasons.toString();
    }
}
