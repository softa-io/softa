package io.softa.starter.flow.design.trigger;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Trigger indicating this flow is invoked as a child flow from a parent.
 * Input variables are provided by the parent's {@code SubflowNodeConfig.inputMapping}.
 */
@Schema(name = "SubflowTrigger")
public record SubflowTrigger() implements TriggerSource {}
