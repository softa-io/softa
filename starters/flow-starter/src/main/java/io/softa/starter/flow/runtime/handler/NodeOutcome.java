package io.softa.starter.flow.runtime.handler;

import java.time.Instant;
import java.util.Map;

/**
 * Result of a node execution — exactly one outcome per node, expressed as a sealed
 * hierarchy so conflicting signals (wait + end, two wait kinds, …) cannot be
 * constructed and the orchestrator's dispatch is an exhaustive switch: adding an
 * outcome kind fails compilation at every consumer until it is handled.
 * <p>
 * {@link #outputs()} is orthogonal to the outcome kind and is merged into the flow
 * variables before the outcome is dispatched — an async dispatch can carry a job
 * handle, a return-value node carries its computed values.
 */
public sealed interface NodeOutcome {

    /** Named outputs produced by this node, merged into {@code vars} before dispatch; never null. */
    Map<String, Object> outputs();

    /** Node finished; execution proceeds along the outgoing transitions. */
    record Completed(Map<String, Object> outputs) implements NodeOutcome {
        public Completed {
            outputs = outputs == null ? Map.of() : outputs;
        }
    }

    /** RETURN_VALUE: terminate the flow gracefully; {@code outputs} doubles as the return envelope. */
    record Ended(Map<String, Object> outputs) implements NodeOutcome {
        public Ended {
            outputs = outputs == null ? Map.of() : outputs;
        }
    }

    /** APPROVAL: suspend the instance until an approval decision arrives. */
    record WaitApproval() implements NodeOutcome {
        @Override
        public Map<String, Object> outputs() {
            return Map.of();
        }
    }

    /** TIMER: suspend until {@code dueAt} fires ({@code null} = recovered by the timer sweep only). */
    record WaitTimer(Instant dueAt) implements NodeOutcome {
        @Override
        public Map<String, Object> outputs() {
            return Map.of();
        }
    }

    /** ASYNC_TASK: task dispatched; suspend until its callback arrives. */
    record WaitAsync(Map<String, Object> outputs) implements NodeOutcome {
        public WaitAsync {
            outputs = outputs == null ? Map.of() : outputs;
        }
    }

    /** SUBFLOW: execute the referenced flow synchronously, then continue past this node. */
    record RunSubflow(Long designId,
                      Map<String, String> inputMapping,
                      String outputVariable,
                      Map<String, String> outputMapping) implements NodeOutcome {
        @Override
        public Map<String, Object> outputs() {
            return Map.of();
        }
    }
}
