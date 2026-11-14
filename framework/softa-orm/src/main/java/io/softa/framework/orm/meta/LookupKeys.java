package io.softa.framework.orm.meta;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * The fields of a related model that a to-one value can be looked up by — what an import column
 * naming that relation should be matched against instead of the related row's numeric id.
 *
 * <p>Each returned field identifies one row on its own, so a lookup by it can only find one record or
 * none. The first is the one to recommend:
 * <ol>
 *   <li>the model's first {@code displayName} field, when it alone is unique — what a person writes
 *       and what an export writes, so a sheet filled by hand or exported elsewhere matches as it is;</li>
 *   <li>otherwise its {@code businessKey}, when one field is left once the components the relation's
 *       own filters already pin are dropped;</li>
 *   <li>then any other field that alone is unique.</li>
 * </ol>
 * "Alone is unique" means a unique index whose fields are that field, plus {@code tenantId} on a
 * multi-tenant model. A name unique only under a parent (a department name per parent department) is
 * not: it names several rows across the tenant.
 *
 * <p>Empty for a code-as-id master ({@code @Model(idStrategy = EXTERNAL_ID)}, e.g. {@code CountryRegion}):
 * its id is the code a person writes, so the bare column already matches. A generated id — numeric or a
 * distributed string — means nothing on a sheet and gets a recommendation either way. Empty too when
 * nothing identifies a row on its own, such as a composite business key: there is no single column to
 * recommend, and a guess would match more than one row.
 *
 * <p>Only a {@code MANY_TO_ONE} is answered. A {@code ONE_TO_ONE} is the row's own sub-record, imported
 * through its fields rather than found by a key, and a to-many cell names several rows, which has no
 * one field to default to.
 */
public final class LookupKeys {

    private LookupKeys() {
    }

    /** The lookup fields for a to-one relation, the recommended first; empty when there is none to offer. */
    public static List<String> of(MetaField relation) {
        if (relation == null
                || relation.getFieldType() != FieldType.MANY_TO_ONE
                || StringUtils.isBlank(relation.getRelatedModel())
                || !ModelManager.existModel(relation.getRelatedModel())) {
            return List.of();
        }
        MetaModel related = ModelManager.getModel(relation.getRelatedModel());
        if (related.getIdStrategy() == IdStrategy.EXTERNAL_ID) {
            return List.of();
        }
        Set<String> alone = uniqueAlone(related);
        Set<String> keys = new LinkedHashSet<>();
        if (!CollectionUtils.isEmpty(related.getDisplayName()) && alone.contains(related.getDisplayName().getFirst())) {
            keys.add(related.getDisplayName().getFirst());
        }
        List<String> businessKey = dropPinned(related.getBusinessKey(), relation);
        if (businessKey.size() == 1) {
            keys.add(businessKey.getFirst());
        }
        keys.addAll(alone);
        return List.copyOf(keys);
    }

    /**
     * The key components still to fill once the relation's declared filters are applied: what is left of
     * {@code keys} after the fields those filters constrain. {@code keys} unchanged when the relation
     * declares no filters, or when every component is pinned.
     */
    public static List<String> dropPinned(List<String> keys, MetaField relation) {
        if (CollectionUtils.isEmpty(keys)) {
            return List.of();
        }
        if (StringUtils.isBlank(relation.getFilters())) {
            return keys;
        }
        Filters declared = Filters.of(relation.getFilters());
        if (declared == null) {
            return keys;
        }
        Set<String> pinned = declared.extractFields();
        List<String> free = keys.stream().filter(k -> !pinned.contains(k)).toList();
        return free.isEmpty() ? keys : free;
    }

    /** The fields of {@code model} that a unique index covers on their own, in index order. */
    private static Set<String> uniqueAlone(MetaModel model) {
        Set<String> fields = new LinkedHashSet<>();
        for (MetaIndex index : ModelManager.getModelIndexes(model.getModelName())) {
            if (!Boolean.TRUE.equals(index.getUniqueIndex()) || CollectionUtils.isEmpty(index.getIndexFields())) {
                continue;
            }
            List<String> members = new ArrayList<>(index.getIndexFields());
            if (model.isMultiTenant()) {
                members.remove(ModelConstant.TENANT_ID);
            }
            if (members.size() == 1) {
                fields.add(members.getFirst());
            }
        }
        return fields;
    }
}
