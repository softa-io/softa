package io.softa.starter.flow.runtime.engine;

import org.junit.jupiter.api.Test;

import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An approver acting on a request withdrawn or terminated since their page loaded is told so, in words they can act
 * on. The refusal used to read "Flow instance is not waiting for approval and cannot be approved: <instance id>",
 * shown as it is on the web and in the app.
 */
class FlowNotWaitingMessageTest {

    private static final String INSTANCE = "c5673fbe-8643-4fa5-99e8-53fe1c617bb4";

    private final FlowActionContextService service = new FlowActionContextService(null, null, null);

    private static FlowExecutionState state(FlowExecutionStatus status) {
        FlowExecutionState state = new FlowExecutionState();
        state.setStatus(status);
        return state;
    }

    @Test
    void anApprovalOfAWithdrawnRequestSaysItWasWithdrawn() {
        assertThatThrownBy(() -> service.requireWaitingApproval(state(FlowExecutionStatus.WITHDRAWN), INSTANCE, "approved"))
                .isInstanceOf(FlowActionValidationException.class)
                .hasMessage("This request can no longer be approved: it was withdrawn by its submitter. "
                        + "Refresh to see where it stands.")
                .hasMessageNotContaining(INSTANCE);
    }

    @Test
    void eachEndSaysWhatBecameOfTheRequest() {
        assertThat(FlowActionContextService.noLongerWaiting(FlowExecutionStatus.CANCELLED, "rejected"))
                .isEqualTo("This request can no longer be rejected: it was terminated. Refresh to see where it stands.");
        assertThat(FlowActionContextService.noLongerWaiting(FlowExecutionStatus.COMPLETED, "approved"))
                .contains("its approval is complete");
        assertThat(FlowActionContextService.noLongerWaiting(FlowExecutionStatus.REJECTED, "withdrawn"))
                .contains("it was rejected");
        assertThat(FlowActionContextService.noLongerWaiting(FlowExecutionStatus.RETURNED, "urged"))
                .contains("returned to its submitter");
        assertThat(FlowActionContextService.noLongerWaiting(null, "approved"))
                .contains("no longer waiting for approval");
    }

    @Test
    void aWaitingRequestPasses() {
        assertThatNoException().isThrownBy(
                () -> service.requireWaitingApproval(state(FlowExecutionStatus.WAITING), INSTANCE, "approved"));
    }
}
