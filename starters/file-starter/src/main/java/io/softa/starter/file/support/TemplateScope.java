package io.softa.starter.file.support;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import io.softa.framework.orm.meta.ModelManager;

/**
 * Which models a page's template list — and its import history — may draw from.
 *
 * <p>The base set is the page's own model plus its child models, which is how one employee page
 * hands out the templates for addresses, family members and the rest. "Child model" comes from
 * {@link ModelManager#getChildModels}, and that is an approximation: it counts the target of every
 * OneToMany, so a REVERSE reference reads the same as a composition. Employee declares
 * {@code managedDepartments} / {@code hrbpDepartments} to say "departments this person leads", and
 * Department therefore became a child of Employee — putting the Department import template in the
 * employee dialog (zingkey/zingkey-hcm#764).
 *
 * <p>A template marked {@code standalone} corrects that from the other side: it says its model's
 * page is the only page it belongs on, so the model is dropped from every OTHER page's set. The
 * host itself is never dropped — a standalone template still appears on its own page, which is the
 * whole point.
 *
 * <p>The subtraction happens on model NAMES rather than as a predicate on the query, for two
 * reasons. {@code standalone != true} would not do it: the column is nullable, and SQL compares
 * NULL to true as UNKNOWN, so every unmarked template — all of them, today — would be filtered out.
 * And {@code ImportHistory} carries no such column at all; reaching it through
 * {@code templateId.standalone} would drop every dynamic import, whose {@code templateId} is null.
 * One set operation serves all three callers and cannot go wrong in either of those ways.
 */
public final class TemplateScope {

    private TemplateScope() {
    }

    /**
     * @param hostModel the model whose page is asking
     * @param standaloneModels models whose templates declare themselves standalone; evaluated only
     *                         when the host actually has child models, so a page with none costs
     *                         no extra query
     * @return the model names whose templates (or import history) this page may show
     */
    public static Set<String> of(String hostModel, Supplier<Set<String>> standaloneModels) {
        Set<String> models = new HashSet<>(ModelManager.getChildModels(hostModel));
        models.add(hostModel);
        if (models.size() > 1) {
            Set<String> standalone = standaloneModels.get();
            models.removeIf(model -> !model.equals(hostModel) && standalone.contains(model));
        }
        return models;
    }
}
