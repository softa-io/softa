package io.softa.starter.flow.runtime.exception;

import lombok.Getter;

import io.softa.framework.base.i18n.I18n;

/**
 * An approval step resolved to nobody under its {@code Error} empty-approver strategy. The action that reached the
 * step — a submission, an approval — is refused ({@code 400}) and changes nothing. The message is the same whatever
 * the step's approver source: whoever reads it cannot fix it and has to ask for an approver to be set up.
 */
@Getter
public class FlowNoApproverException extends FlowActionValidationException {

    /** The refusal, translated: {0} is the step's label, else its node id. */
    public static final String MESSAGE = "No approver was found for step ''{0}''. "
            + "Ask your HR administrator to set one up, then try again.";

    /** The step that resolved to nobody. */
    private final String nodeId;

    public FlowNoApproverException(String nodeId, String nodeLabel) {
        super(messageOf(nodeId, nodeLabel));
        this.nodeId = nodeId;
    }

    /** The refusal for the step, translated into the current language; the approval path preview reports it too. */
    public static String messageOf(String nodeId, String nodeLabel) {
        return I18n.get(MESSAGE, nodeLabel != null && !nodeLabel.isBlank() ? nodeLabel : nodeId);
    }
}
