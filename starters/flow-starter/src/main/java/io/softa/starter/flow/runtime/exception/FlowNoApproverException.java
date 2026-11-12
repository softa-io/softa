package io.softa.starter.flow.runtime.exception;

import java.util.List;

import io.softa.framework.base.i18n.I18n;

/**
 * An approval step resolved to nobody under its {@code Error} empty-approver strategy — or under {@code AssignToRole},
 * its roles leaving nobody either. A submission that reaches the step is refused ({@code 400}) and changes nothing;
 * reached after an approval, the instance fails with it, the approval kept, and its initiator and the tenant's
 * administrators are told. The message says why the step found nobody, when its approver source can
 * tell — the employee has no manager who can approve, nobody who can holds its roles — and who can set one up: whoever
 * reads it cannot fix it themselves.
 */
public class FlowNoApproverException extends FlowActionValidationException {

    /** The refusal, translated: {0} is the step's label, else its node id. */
    public static final String MESSAGE = "No approver was found for step ''{0}''. "
            + "Ask your HR administrator to set one up, then try again.";

    /** The refusal with why the step found nobody, translated: {0} is the step's label, else its node id; {1} why. */
    public static final String REASON_MESSAGE = "No approver was found for step ''{0}'': {1}. "
            + "Ask your HR administrator to set one up, then try again.";

    /**
     * The refusal for a step that went to roles and found nobody there either, translated: {0} is the step's label,
     * else its node id; {1} the roles.
     */
    public static final String ROLES_MESSAGE = "No approver was found for step ''{0}'', and nobody in {1} can take it. "
            + "Ask your HR administrator to set one up, then try again.";

    /** {@code reason}: why the step found nobody, null when its source cannot tell. */
    public FlowNoApproverException(String nodeId, String nodeLabel, String reason) {
        super(messageOf(nodeId, nodeLabel, reason));
    }

    private FlowNoApproverException(String message) {
        super(message);
    }

    /** A step that went to {@code roles} under {@code AssignToRole} and found nobody there either. */
    public static FlowNoApproverException inRoles(String nodeId, String nodeLabel, List<String> roles) {
        return new FlowNoApproverException(messageOf(nodeId, nodeLabel, roles));
    }

    /** The refusal for the step, translated into the current language; the approval path preview reports it too. */
    public static String messageOf(String nodeId, String nodeLabel) {
        return I18n.get(MESSAGE, labelOf(nodeId, nodeLabel));
    }

    /** {@link #messageOf(String, String)} saying why, when {@code reason} does. */
    public static String messageOf(String nodeId, String nodeLabel, String reason) {
        return reason == null || reason.isBlank()
                ? messageOf(nodeId, nodeLabel)
                : I18n.get(REASON_MESSAGE, labelOf(nodeId, nodeLabel), reason);
    }

    /** {@link #messageOf(String, String)} for a step that went to {@code roles} and found nobody there either. */
    public static String messageOf(String nodeId, String nodeLabel, List<String> roles) {
        return I18n.get(ROLES_MESSAGE, labelOf(nodeId, nodeLabel), String.join(", ", roles));
    }

    private static String labelOf(String nodeId, String nodeLabel) {
        return nodeLabel != null && !nodeLabel.isBlank() ? nodeLabel : nodeId;
    }
}
