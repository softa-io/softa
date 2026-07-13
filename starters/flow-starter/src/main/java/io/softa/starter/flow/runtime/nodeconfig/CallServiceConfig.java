package io.softa.starter.flow.runtime.nodeconfig;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code CALL_SERVICE} nodes.
 * <p>
 * {@code beanName} / {@code methodName} may be {@code {{ expr }}}; {@code args} elements support
 * placeholder interpolation; {@code argTypes} (optional) are FQCNs (or primitive names) that must
 * match {@code args} by arity. Resolution is owned by the executor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallServiceConfig {

    /** Spring bean name to invoke (required). */
    private String beanName;

    /** Method name to invoke (required). */
    private String methodName;

    /** Positional arguments; each element supports placeholder interpolation. */
    private List<Object> args;

    /** Optional argument types (FQCN / primitive name); when present, must match {@code args} arity. */
    private List<String> argTypes;
}
