package io.softa.starter.flow.service;

import java.util.List;

import io.softa.starter.flow.dto.FlowBatchActionResult;
import io.softa.starter.flow.dto.FlowReassignPreview;
import io.softa.starter.flow.entity.FlowDelegation;

/**
 * What an administrator does to approvals from the monitoring console — terminate them, run again the steps after
 * an approval that failed in them, move an approver's pending tasks to someone else, set up a delegation for an
 * approver. Terminating, running again and reassigning reach only the instances the administrator's own permissions
 * read (the console's data scope), each acted on separately; delegating on someone's behalf affects all of their
 * tasks and is gated by a permission of its own.
 */
public interface FlowAdministrationService {

    /** Terminates each instance the administrator may administer; the others are refused. */
    FlowBatchActionResult terminate(List<String> instanceIds, String reason, String operatorId);

    /**
     * Runs again the steps after an approval of each instance the administrator may administer that failed in them
     * (zingkey-hcm#2385); the others are refused. Granted with terminating: whoever may end a failed approval may
     * also carry it on.
     */
    FlowBatchActionResult retry(List<String> instanceIds, String comment, String operatorId);

    /** How many of {@code fromActorId}'s pending approval tasks a reassignment would move. */
    FlowReassignPreview previewReassign(String fromActorId, String toActorId);

    /** Moves each of {@code fromActorId}'s pending approval tasks within the data scope to {@code toActorId}. */
    FlowBatchActionResult reassign(String fromActorId, String toActorId, String reason, String operatorId);

    /** A delegation rule for another approver: from then on their arriving tasks go to the delegate. */
    FlowDelegation delegateOnBehalf(FlowDelegation delegation);

    /** Every delegation rule of the tenant, newest first. */
    List<FlowDelegation> listDelegations();

    /** Deactivates any delegation rule of the tenant. */
    void cancelDelegation(Long delegationId);
}
