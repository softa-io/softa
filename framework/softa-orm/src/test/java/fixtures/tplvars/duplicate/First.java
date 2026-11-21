package fixtures.tplvars.duplicate;

import io.softa.framework.base.annotation.TemplateVariableSet;

/**
 * One of two records declaring the same name. Kept outside {@code io.softa}, which every
 * application scans: a duplicate there would stop any test that starts a context.
 */
@TemplateVariableSet(name = "test.declared-twice")
public record First(String code) {
}
