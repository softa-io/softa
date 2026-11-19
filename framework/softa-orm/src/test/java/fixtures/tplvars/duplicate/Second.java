package fixtures.tplvars.duplicate;

import io.softa.framework.base.annotation.TemplateVariables;

/** The other record declaring {@code test.declared-twice}; see {@link First}. */
@TemplateVariables(name = "test.declared-twice")
public record Second(String other) {
}
