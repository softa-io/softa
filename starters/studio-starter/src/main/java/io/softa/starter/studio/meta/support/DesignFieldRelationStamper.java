package io.softa.starter.studio.meta.support;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.metadata.ddl.ReferenceColumnResolver;
import io.softa.starter.metadata.ddl.context.ReferencedColumn;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.service.DesignFieldService;

/**
 * Stamps the system-computed {@code relatedFieldType} (and the mirrored {@code length}/{@code scale})
 * onto a DesignField write row, so a TO_ONE foreign key's physical type is materialized at edit time.
 *
 * <p>Studio design CRUD goes through the generic model controller / {@code ModelService} (row maps),
 * so {@code DesignFieldController} overrides the create/update API and invokes this before delegating.
 * Reuses the shared {@link ReferenceColumnResolver} rule; the referenced column is sourced by a
 * targeted query against the design workspace. The logical {@code fieldType} (MANY_TO_ONE /
 * ONE_TO_ONE) is left untouched — only {@code relatedFieldType} / {@code length} / {@code scale}.
 *
 * <p>Single-hop only (the saved field's own type). Cross-version propagation to dependent FKs when a
 * referenced column itself changes is a separate follow-up.
 */
@Component
public class DesignFieldRelationStamper {

    @Autowired
    private DesignFieldService fieldService;

    /**
     * Resolve and stamp {@code relatedFieldType} (+ mirrored width) into a single write row. A
     * partial update that does not touch the relation is left untouched; a row that is (or becomes)
     * a non-FK field has {@code relatedFieldType} cleared.
     */
    public void stamp(Map<String, Object> row) {
        if (row == null) {
            return;
        }
        Object idValue = row.get("id");
        boolean touchesRelation = row.containsKey("fieldType")
                || row.containsKey("relatedModel") || row.containsKey("relatedField");
        // Partial update that doesn't change the relation: relatedFieldType is already current.
        if (idValue != null && !touchesRelation) {
            return;
        }
        DesignField existing = idValue != null ? fieldService.getById(IdUtils.convertIdToLong(idValue)).orElse(null) : null;

        FieldType fieldType = resolveFieldType(row.get("fieldType"), existing);
        String relatedModel = resolveString(row, "relatedModel", existing == null ? null : existing.getRelatedModel());
        String relatedField = resolveString(row, "relatedField", existing == null ? null : existing.getRelatedField());
        String fieldName = resolveString(row, "fieldName", existing == null ? null : existing.getFieldName());
        Long appId = row.get("appId") != null
                ? IdUtils.convertIdToLong(row.get("appId")) : (existing == null ? null : existing.getAppId());
        // design_field is per-env (businessKey {envId, modelName, fieldName}), so the
        // referenced column must be looked up within THIS env — an appId-only lookup matches the same
        // (modelName, fieldName) in every env and makes searchOne throw once a second env exists (the
        // standard dev→staging clone). On create the envId is already stamped onto the row by
        // DesignWriteStamper.stampCreate; on update it is carried by the persisted `existing`.
        Long envId = row.get("envId") != null
                ? IdUtils.convertIdToLong(row.get("envId")) : (existing == null ? null : existing.getEnvId());

        ReferencedColumn referenced = (appId == null || envId == null) ? null
                : ReferenceColumnResolver.resolveReferenced(
                        fieldType, relatedModel, relatedField,
                        (model, name) -> findReferenced(appId, envId, model, name),
                        fieldName);
        if (referenced != null) {
            row.put("relatedFieldType", referenced.fieldType().getType());
            row.put("length", referenced.length());
            row.put("scale", referenced.scale());
        } else {
            // Not a (resolvable) FK — clear any stale physical type.
            row.put("relatedFieldType", null);
        }
    }

    private ReferencedColumn findReferenced(Long appId, Long envId, String relatedModel, String relatedFieldName) {
        Filters filters = new Filters()
                .eq(DesignField::getAppId, appId)
                .eq(DesignField::getEnvId, envId)
                .eq(DesignField::getModelName, relatedModel)
                .eq(DesignField::getFieldName, relatedFieldName);
        DesignField referenced = fieldService.searchOne(new FlexQuery(filters)).orElse(null);
        if (referenced == null || referenced.getFieldType() == null) {
            return null;
        }
        return new ReferencedColumn(referenced.getFieldType(), referenced.getLength(), referenced.getScale());
    }

    private static FieldType resolveFieldType(Object value, DesignField existing) {
        if (value instanceof FieldType fieldType) {
            return fieldType;
        }
        if (value instanceof String str && !str.isBlank()) {
            return FieldType.of(str);
        }
        return existing == null ? null : existing.getFieldType();
    }

    private static String resolveString(Map<String, Object> row, String key, String fallback) {
        if (row.containsKey(key)) {
            Object value = row.get(key);
            return value == null ? null : String.valueOf(value);
        }
        return fallback;
    }
}
