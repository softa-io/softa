package io.softa.starter.flow.compiler.validation;

import java.util.List;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;

/**
 * Validates a single aspect of a design-time flow definition during compilation.
 *
 * <p>Implementations collect <em>all</em> errors as {@link CompileDiagnostic} entries
 * and return them as a list (fail-late), rather than throwing on the first error.
 * {@link io.softa.starter.flow.compiler.DefaultFlowCompiler} aggregates the lists from
 * all validators and throws a single {@link io.softa.starter.flow.api.FlowCompileException}
 * if any of them is an error; a {@link CompileDiagnostic.Severity#WARNING warning} is advice
 * the editor shows and does not stop a publish.</p>
 */
@FunctionalInterface
public interface FlowValidator {

    List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context);
}
