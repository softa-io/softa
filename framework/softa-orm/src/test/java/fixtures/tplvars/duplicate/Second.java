package fixtures.tplvars.duplicate;

import io.softa.framework.base.annotation.TemplateVariableSet;

/** The other record declaring {@code test.declared-twice}; see {@link First}. */
@TemplateVariableSet(name = "test.declared-twice")
public record Second(String other) {
}
