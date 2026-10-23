package io.softa.framework.orm.service.relation;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.ReflectTool;

/**
 * Enforces the relation delete strategy ({@code @Field.onDelete}) when a "One" row is
 * deleted: every OnDelete TO_ONE FK pointing at the deleted model is consulted and its policy applied
 * — RESTRICT (block), CASCADE (delete the referrers), SET_NULL (null the FK on a hard delete). Unset
 * ({@code onDelete == null}) = KEEP and never reaches here (the reverse-reference index only holds
 * non-KEEP fields).
 *
 * <p>Invoked from {@code ModelServiceImpl.deleteByIds} inside its {@code @Transactional} boundary,
 * after the soft-delete idempotency filter, before the physical delete — so a RESTRICT violation rolls
 * the whole delete back, and CASCADE recursion (via {@code ReflectTool -> ModelService.deleteByIds})
 * re-applies permission / soft-delete / changelog uniformly. CASCADE needs no runtime cycle guard: the
 * CASCADE graph is validated acyclic at boot ({@code ModelManager.validateCascadeAcyclic}), so the
 * recursion always terminates.
 *
 * <p>Scope: the reverse scan counts only live referrers ({@code deleted=false} is the
 * auto default), is active-agnostic (overrides the auto {@code active=true}), and its tenant scope
 * follows the deleted model — a multi-tenant One stays in the current tenant (isolation guarantees
 * same-tenant referrers), a shared/non-multi-tenant One is a platform op scanned across all tenants.
 */
@Component
public class RelationDeleteHandler {

    /**
     * @param modelName    the deleted ("One") model
     * @param deletableIds the ids of the rows being deleted. A TO_ONE FK always references the related
     *                     model's id, so the deleted ids <b>are</b> the reverse-scan keys —
     *                     no business-key columns are needed.
     */
    public void handle(String modelName, List<Serializable> deletableIds) {
        MetaModel model = ModelManager.getModel(modelName);
        List<MetaField> onDeleteRefFields = model.getOnDeleteRefFields();
        if (CollectionUtils.isEmpty(onDeleteRefFields) || CollectionUtils.isEmpty(deletableIds)) {
            return;
        }
        // A shared (non-multi-tenant) One may be referenced across tenants; its deletion is a
        // platform operation, so scan/cascade across all tenants. A multi-tenant One stays in the
        // current tenant. Open the cross-tenant window WITHOUT @CrossTenant (which also skips
        // permission checks).
        boolean shared = !model.isMultiTenant();
        if (shared && !ContextHolder.getContext().isCrossTenant()) {
            Context crossTenant = ContextHolder.cloneContext();
            crossTenant.setCrossTenant(true);
            ContextHolder.runWith(crossTenant, () -> apply(modelName, deletableIds, onDeleteRefFields));
        } else {
            apply(modelName, deletableIds, onDeleteRefFields);
        }
    }

    private void apply(String modelName, List<Serializable> ids, List<MetaField> onDeleteRefFields) {
        // RESTRICT first — cheap existence probes that fail fast before any cascade write.
        for (MetaField f : onDeleteRefFields) {
            if (OnDelete.RESTRICT == f.getOnDelete()) {
                restrict(modelName, ids, f);
            }
        }
        for (MetaField f : onDeleteRefFields) {
            switch (f.getOnDelete()) {
                case CASCADE -> cascade(ids, f);
                case SET_NULL -> setNull(modelName, ids, f);
                default -> { /* RESTRICT handled above; KEEP never reaches here */ }
            }
        }
    }

    private void restrict(String deletedModel, List<Serializable> ids, MetaField f) {
        long count = asSystem(() -> ReflectTool.count(f.getModelName(), reverseFilters(f, ids)));
        Assert.isTrue(count == 0,
                "Cannot delete {0}: {1} row(s) in {2}.{3} still reference it (onDelete=RESTRICT).",
                deletedModel, count, f.getModelName(), f.getFieldName());
    }

    private void cascade(List<Serializable> ids, MetaField f) {
        List<Serializable> referrerIds = referrerIds(ids, f);
        if (!referrerIds.isEmpty()) {
            // Recurses ModelService.deleteByIds (CASCADE acyclic, boot-validated) → soft/hard per the referrer's own model.
            ReflectTool.deleteList(f.getModelName(), referrerIds);
        }
    }

    private void setNull(String deletedModel, List<Serializable> ids, MetaField f) {
        // SET_NULL only severs the link when the One is physically gone; a soft-deleted (recoverable)
        // One keeps the link (no-op = KEEP) so a later restore still resolves it.
        if (ModelManager.isSoftDeleted(deletedModel)) {
            return;
        }
        List<Serializable> referrerIds = referrerIds(ids, f);
        if (referrerIds.isEmpty()) {
            return;
        }
        List<Map<String, Object>> updates = referrerIds.stream().map(id -> {
            Map<String, Object> row = new HashMap<>(2);
            row.put(ModelConstant.ID, id);
            row.put(f.getFieldName(), null);   // present-but-null → SET fk = NULL (patch update)
            return row;
        }).toList();
        ReflectTool.updateList(f.getModelName(), updates);
    }

    /**
     * Reads the referrers as the system. Whether a row is still referenced does not depend on who asks:
     * read as the caller, a referrer outside their range did not count, a RESTRICT let the delete through,
     * and a CASCADE or SET_NULL left it pointing at nothing. Their field guards had the same effect — a
     * referrer shown only by its label hides the foreign key the probe matches on. Nothing read here is
     * returned to the caller; the writes that follow on the ids are judged as the caller.
     */
    private static <T> T asSystem(Supplier<T> read) {
        Context ctx = ContextHolder.cloneContext();
        ctx.setSkipPermissionCheck(true);
        return ContextHolder.callWith(ctx, read::get);
    }

    /**
     * Ids of the live referrers whose FK points at one of the deleted ids (empty when nothing references
     * them). Guarded by a per-level volume cap (Tier 2): a single delete may not CASCADE / SET_NULL more
     * than {@code MAX_BATCH_SIZE} referrers — an accidental high-fanout delete fails fast instead of
     * running away. One query, fetched with {@code LIMIT MAX_BATCH_SIZE + 1}: enough to detect an
     * over-limit set, but the full (possibly huge) list is never loaded — the cap stays memory-bounded
     * without a separate {@code count}.
     */
    private List<Serializable> referrerIds(List<Serializable> ids, MetaField f) {
        List<Serializable> referrerIds = asSystem(() ->
                ReflectTool.getIds(f.getModelName(), reverseFilters(f, ids), BaseConstant.MAX_BATCH_SIZE + 1));
        Assert.isTrue(referrerIds.size() <= BaseConstant.MAX_BATCH_SIZE,
                "onDelete={0} on {1}.{2} would affect more than the MAX_BATCH_SIZE limit ({3}) referrer "
                        + "row(s) — narrow the delete, or reap the hierarchy in batches (application code).",
                f.getOnDelete(), f.getModelName(), f.getFieldName(), BaseConstant.MAX_BATCH_SIZE);
        return referrerIds;
    }

    /**
     * Reverse-reference filter on the referrer model: its FK column IN the deleted ids, restricted to
     * live referrers ({@code deleted=false} is auto-injected) and made active-agnostic (overriding the
     * auto {@code active=true}). Tenant scope is governed by the surrounding context (see {@link #handle}).
     */
    private Filters reverseFilters(MetaField f, List<Serializable> ids) {
        Filters filters = new Filters().in(f.getFieldName(), ids);
        if (ModelManager.isActiveControl(f.getModelName())) {
            filters.in(ModelConstant.ACTIVE_CONTROL_FIELD, List.of(true, false));
        }
        return filters;
    }
}
