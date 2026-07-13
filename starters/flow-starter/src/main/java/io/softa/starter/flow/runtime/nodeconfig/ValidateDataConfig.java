package io.softa.starter.flow.runtime.nodeconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed input config for {@code VALIDATE_DATA} nodes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidateDataConfig {

    /** AviatorScript boolean expression; the flow fails when it evaluates false (required). */
    private String expression;

    /** Failure message; supports {@code {{ }}} interpolation (required). */
    private String exceptionMsg;
}
