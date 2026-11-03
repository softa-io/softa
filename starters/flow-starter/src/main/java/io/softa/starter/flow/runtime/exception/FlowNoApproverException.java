package io.softa.starter.flow.runtime.exception;

import java.util.List;
import lombok.Getter;

import io.softa.framework.base.i18n.I18n;

/**
 * An approval step resolved to nobody under its {@code Error} empty-approver strategy — or under {@code AssignToRole},
 * its roles leaving nobody either. A submission that reaches the step is refused ({@code 400}) and changes nothing;
 * reached after an approval, the instance fails with it, the approval kept, and its initiator and the tenant's
 * administrators are told (zingkey-hcm#2385). The message is the same whatever the step's approver source, the roles
 * named: whoever reads it cannot fix it and has to ask for an approver to be set up.
 */
@Getter
public class FlowNoApproverException extends FlowActionValidationException {

    /** The refusal, translated: {0} is the step's label, else its node id. */
    public static final String MESSAGE = "No approver was found for step ''{0}''. "
            + "Ask your HR administrator to set one up, then try again.";

    /**
     * The refusal for a step that went to roles and found nobody there either, translated: {0} is the step's label,
     * else its node id; {1} the roles.
     */
    public static final String ROLES_MESSAGE = "No approver was found for step ''{0}'', and nobody in {1} can take it "
            + "but the submitter or the employee the record is about. Ask your HR administrator to set one up, then "
            + "try again.";

    /** The step that resolved to nobody. */
    private final String nodeId;

    public FlowNoApproverException(String nodeId, String nodeLabel) {
        super(messageOf(nodeId, nodeLabel));
        this.nodeId = nodeId;
    }

    private FlowNoApproverException(String nodeId, List<String> roles, String nodeLabel) {
        super(messageOf(nodeId, nodeLabel, roles));
        this.nodeId = nodeId;
    }

    /** A step that went to {@code roles} under {@code AssignToRole} and found nobody there either. */
    public static FlowNoApproverException inRoles(String nodeId, String nodeLabel, List<String> roles) {
        return new FlowNoApproverException(nodeId, roles, nodeLabel);
    }

    /** The refusal for the step, translated into the current language; the approval path preview reports it too. */
    public static String messageOf(String nodeId, String nodeLabel) {
        return I18n.get(MESSAGE, labelOf(nodeId, nodeLabel));
    }

    /** {@link #messageOf(String, String)} for a step that went to {@code roles} and found nobody there either. */
    public static String messageOf(String nodeId, String nodeLabel, List<String> roles) {
        return I18n.get(ROLES_MESSAGE, labelOf(nodeId, nodeLabel), String.join(", ", roles));
    }

    private static String labelOf(String nodeId, String nodeLabel) {
        return nodeLabel != null && !nodeLabel.isBlank() ? nodeLabel : nodeId;
    }
}
