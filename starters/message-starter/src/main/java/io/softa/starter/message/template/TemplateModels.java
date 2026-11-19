package io.softa.starter.message.template;

import java.util.Map;
import java.util.Set;

import io.softa.framework.orm.meta.MetaField;

/**
 * What the template rules need to know of the models a template is bound to — kept behind an
 * interface so the rules can be exercised without the metadata of a running application.
 */
public interface TemplateModels {

    /** Whether the model exists. */
    boolean exists(String model);

    /** The field, or null when the model does not have it. */
    MetaField field(String model, String fieldName);

    /**
     * Why {@code path} cannot be read on {@code model} — a part that is not a field, or a path
     * followed past something that is not a to-one relation — or null when it can.
     */
    String pathProblem(String model, String path);

    /**
     * The fields the current caller can never see, per model: sensitive fields no role of theirs
     * grants, and relations onto models they can read nothing of. Empty for a caller with full
     * access and for a write no person makes (seed loading, a system job).
     */
    Map<String, Set<String>> withheld();
}
