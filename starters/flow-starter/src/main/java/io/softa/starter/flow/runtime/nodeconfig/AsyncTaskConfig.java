package io.softa.starter.flow.runtime.nodeconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code ASYNC_TASK} nodes.
 * <p>
 * The envelope keys are fixed; {@code dataTemplate} is a free-shape payload handed
 * to the async task handler after {@code {{ }}} resolution by the executor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncTaskConfig {

    /** Registered async task handler code; supports {@code {{ }}} interpolation (required). */
    private String asyncTaskHandlerCode;

    /** Free-shape parameters template passed to the handler. */
    private Object dataTemplate;
}
