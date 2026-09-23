package io.softa.starter.permission.scope;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default {@link DepartmentCascadePathResolver} — walks sys_field metadata to find
 * the canonical dot-path from the queried model to its associated {@code
 * Department} record.
 *
 * <p><b>Registration:</b> NOT annotated with {@code @Component} directly —
 * {@code @ConditionalOnMissingBean} on a component class has subtle ordering
 * issues with the component scan (the conditional evaluator may see this
 * bean's own type as already present and skip it). Instead, this class is
 * registered as a {@code @Bean} in {@link PermissionScopeConfig} with the
 * conditional applied there, which Spring evaluates reliably at config time.
 *
 * <h3>Lookup strategy (deliberately narrow)</h3>
 * <ol>
 *   <li><b>Self</b> — the queried model IS {@code Department}. Returns the empty path: the
 *       department is the row, so the filter reads its own {@code idPath} with no hop. Without
 *       this the tree model is the one thing a department scope cannot be written against —
 *       an HRBP could be granted "departments I manage" over every model that merely
 *       <i>references</i> a department, but not over the departments themselves.</li>
 *   <li><b>Direct anchor</b> — model has a ToOne field literally named
 *       {@code departmentId} whose {@code relatedModel == "Department"}.
 *       Returns {@code "departmentId"}, unless that field is a dynamic cascaded
 *       field (no physical column) — then its declared cascade path (e.g.
 *       {@code "employeeId.departmentId"}) is returned so filters join real FKs.</li>
 *   <li><b>Indirect via employee</b> — model has {@code employeeId} pointing
 *       at {@code Employee}, and {@code Employee} itself satisfies step 1.
 *       Returns {@code "employeeId.departmentId"}.</li>
 *   <li><b>Empty</b> — anything else. The HR contributors throw
 *       {@code IllegalStateException} — by-design fail-loud surfacing of
 *       a metadata gap.</li>
 * </ol>
 *
 * <p>We intentionally do NOT recurse via a general BFS — that would let
 * field-iteration order silently pick {@code additionalDepartmentId} or
 * other secondary dept references, with subtle access-control consequences.
 *
 * <p>Results are cached per model in a {@link ConcurrentHashMap}. softa's
 * sys_field metadata is loaded at boot and very rarely mutated at runtime,
 * so the cache stays valid for the application lifetime.
 */
@Slf4j
public class DefaultDepartmentCascadePathResolver implements DepartmentCascadePathResolver {

    private static final String DEPT_MODEL = "Department";
    private static final String EMPLOYEE_MODEL = "Employee";
    private static final String DEPT_FIELD = "departmentId";
    private static final String EMPLOYEE_FIELD = "employeeId";
    private static final String SELF_PATH = DepartmentCascadePathResolver.SELF_PATH;

    private final ConcurrentMap<String, Optional<String>> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<String> resolve(String modelName) {
        if (modelName == null || modelName.isEmpty()) return Optional.empty();
        return cache.computeIfAbsent(modelName, this::compute);
    }

    private Optional<String> compute(String modelName) {
        // Self-reference: the department IS the row, so there is nothing to hop through and the
        // prefix match runs against its own idPath. Gated on the field actually existing rather
        // than on the name alone — the empty path is only meaningful for a model the subtree
        // filter can be written against, and idPath is what makes that true.
        if (DEPT_MODEL.equals(modelName)
                && ModelManager.getModelFieldOrNull(modelName, IdPath.FIELD) != null) {
            return Optional.of(SELF_PATH);
        }
        MetaField dept = ModelManager.getModelFieldOrNull(modelName, DEPT_FIELD);
        if (dept != null
                && FieldType.TO_ONE_TYPES.contains(dept.getFieldType())
                && DEPT_MODEL.equals(dept.getRelatedModel())) {
            // A dynamic cascaded departmentId (e.g. cascadedField "employeeId.departmentId")
            // has NO physical column — filtering it directly would hit a non-existent
            // column (e.g. overtime_request.department_id). Use its declared cascade path
            // so the scope filter joins the real FK chain instead. A physically-backed
            // departmentId keeps the direct anchor.
            return Optional.of(dept.isDynamicCascadedField() ? dept.getCascadedField() : DEPT_FIELD);
        }
        if (hasToOneTo(modelName, EMPLOYEE_FIELD, EMPLOYEE_MODEL)
                && hasToOneTo(EMPLOYEE_MODEL, DEPT_FIELD, DEPT_MODEL)) {
            return Optional.of(EMPLOYEE_FIELD + "." + DEPT_FIELD);
        }
        log.debug("DepartmentCascadePathResolver — no path to Department for model {}", modelName);
        return Optional.empty();
    }

    private boolean hasToOneTo(String modelName, String fieldName, String expectedRelatedModel) {
        MetaField f = ModelManager.getModelFieldOrNull(modelName, fieldName);
        if (f == null) return false;
        if (!FieldType.TO_ONE_TYPES.contains(f.getFieldType())) return false;
        return expectedRelatedModel.equals(f.getRelatedModel());
    }
}
