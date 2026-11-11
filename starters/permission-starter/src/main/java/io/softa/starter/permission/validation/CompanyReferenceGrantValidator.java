package io.softa.starter.permission.validation;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;

/**
 * A company a write names is one the writer's roles are granted for that write.
 *
 * <p>Row scope bounds a record by its {@code companyId}, the company it belongs to. A record may name
 * other companies too — the entity a contract is signed under, the one an employee is employed by — and
 * nothing bounded those: the reference was resolved against the company list, which reaches the
 * companies of every role the user holds, and a role with no company grant (the self-service role)
 * reaches them all. A role granted one company could create or import a record under another's.
 *
 * <p>Every stored to-one field onto the company model is judged, except the {@code companyId} axis that
 * row scope already bounds: on create when it is set, on update only when the write changes it, against
 * the company grants of the roles holding that write ({@link PermissionService#getGrantedCompanyIds}) — on
 * update only those reaching the row, as a role answers for its own rows and its own companies together.
 * A record already naming a company outside the grant — set by an administrator, or by the system — stays
 * editable in its other fields. Writes made without permission checks (the system, seed data, a service
 * acting for the caller) are not the caller's choice and pass, as do callers whose write is unrestricted.
 *
 * <p>Band 100: a field rule, accumulated with the others.
 */
@Component
@Order(100)
public class CompanyReferenceGrantValidator implements ModelWriteValidator {

    private final PermissionService permissionService;
    private final ModelService<?> modelService;

    /** Model → its fields judged here; metadata is fixed once the application is up. */
    private final Map<String, List<String>> referencesByModel = new ConcurrentHashMap<>();

    public CompanyReferenceGrantValidator(@Lazy PermissionService permissionService,
                                          @Lazy ModelService<?> modelService) {
        this.permissionService = permissionService;
        this.modelService = modelService;
    }

    @Override
    public boolean supports(String modelName) {
        return !referencesOf(modelName).isEmpty();
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        for (String field : referencesOf(ctx.modelName())) {
            check(ctx, field, AccessType.CREATE, idOf(ctx.row().get(field)), null);
        }
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        Map<String, Object> original = ctx.originalRow();
        for (String field : referencesOf(ctx.modelName())) {
            if (!ctx.patch().containsKey(field)) {
                continue;
            }
            Long next = idOf(ctx.patch().get(field));
            if (original != null && Objects.equals(next, idOf(original.get(field)))) {
                continue;
            }
            check(ctx, field, AccessType.UPDATE, next, ctx.id());
        }
    }

    /**
     * The fields of {@code modelName} that name a company ({@link PermissionService#namesCompany}), the company
     * axis left out: row scope bounds that one.
     */
    List<String> referencesOf(String modelName) {
        return referencesByModel.computeIfAbsent(modelName, model -> {
            if (!ModelManager.existModel(model) || ModelManager.isProjectionModel(model)) {
                return List.of();
            }
            return ModelManager.getModelFields(model).stream()
                    .filter(PermissionService::namesCompany)
                    .map(MetaField::getFieldName)
                    .filter(name -> !ModelConstant.COMPANY_FIELD.equals(name))
                    .sorted()
                    .toList();
        });
    }

    private void check(WriteContext ctx, String field, AccessType access, Long company, Serializable rowId) {
        if (company == null) {
            return;
        }
        Set<Long> granted = grantedFor(ctx, access, rowId);
        if (granted == null || granted.contains(company)) {
            return;
        }
        // The field's label is put in front of the message, so it starts with the company itself.
        ctx.reject(field, "\"" + nameOf(company) + "\" is outside the companies your role is granted."
                + " Choose one of those, or ask an administrator to grant your role this company.");
    }

    /** The grant for this write, worked out once per write, action and row. */
    @SuppressWarnings("unchecked")
    private Set<Long> grantedFor(WriteContext ctx, AccessType access, Serializable rowId) {
        Map<String, Set<Long>> byRow = (Map<String, Set<Long>>) ctx.scratch()
                .computeIfAbsent("grantedCompanies", key -> new HashMap<>());
        String key = access + "|" + rowId;
        if (!byRow.containsKey(key)) {
            byRow.put(key, rowId == null
                    ? permissionService.getGrantedCompanyIds(ctx.modelName(), access)
                    : permissionService.getGrantedCompanyIds(ctx.modelName(), access, rowId));
        }
        return byRow.get(key);
    }

    /** The company's name for the message, read past row scope: it is the one the writer just named. */
    private String nameOf(Long company) {
        Map<?, String> names = ContextUtils.withoutPermissionChecks(() -> modelService.getDisplayNames(
                ModelConstant.COMPANY_MODEL, new Filters().eq(ModelConstant.ID, company)));
        return names.values().stream().filter(Objects::nonNull).findFirst().orElse(String.valueOf(company));
    }

    static Long idOf(Object value) {
        Object id = value instanceof Map<?, ?> map ? map.get(ModelConstant.ID) : value;
        if (id == null || String.valueOf(id).isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(id).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
