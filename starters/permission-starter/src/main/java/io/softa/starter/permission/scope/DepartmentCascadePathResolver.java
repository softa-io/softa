package io.softa.starter.permission.scope;

import java.util.Optional;

/**
 * SPI: resolves a queried model name to the dot-path field expression that
 * reaches its associated {@code Department} record — used by the HR
 * scope contributors ({@code DepartmentSubtreeScopeContributor} /
 * {@code ManagedDepartmentsScopeContributor}) to emit dot-path filters on
 * {@code <path>.idPath}.
 *
 * <p>Example paths:
 * <ul>
 *   <li>{@code "departmentId"} — model has a ManyToOne directly to
 *       Department (e.g. Employee, EmpPreOnboarding)</li>
 *   <li>{@code "employeeId.departmentId"} — model reaches Department via
 *       its Employee FK (e.g. LeaveRequest, OvertimeRequest, Expense)</li>
 *   <li>{@code Optional.empty()} — model has no resolvable path to
 *       Department. The contributor treats this as a config error and
 *       throws {@code IllegalStateException} (since the upstream
 *       {@code ScopeApplicabilityResolver} would only let DEPT_SUBTREE /
 *       MANAGED_DEPARTMENTS reach the contributor if the model has a
 *       {@code departmentId} field — empty here means the field exists
 *       but its metadata-side ToOne→Department declaration is missing).</li>
 * </ul>
 *
 * <p>{@link DefaultDepartmentCascadePathResolver} is the standard implementation
 * baked into this app — it walks {@code ModelManager} metadata to find
 * the canonical anchor (direct {@code departmentId} or indirect {@code
 * employeeId.departmentId}). It's auto-wired unless the application context
 * already provides a {@code DepartmentCascadePathResolver} bean.
 *
 * <h3>When the default is enough (99% case)</h3>
 * Don't override if your model satisfies either:
 * <ul>
 *   <li>has a {@code departmentId} ToOne field whose
 *       {@code relatedModel == "Department"}; OR</li>
 *   <li>has an {@code employeeId} ToOne field whose
 *       {@code relatedModel == "Employee"}, and {@code Employee} itself
 *       satisfies the rule above.</li>
 * </ul>
 *
 * <h3>When you MUST override</h3>
 * Three concrete situations where the default returns ambiguously /
 * empty. Register your own {@code @Component DepartmentCascadePathResolver}
 * bean — the default's {@code @ConditionalOnMissingBean} steps aside.
 *
 * <ol>
 *   <li><b>Multiple Department FKs on the same model — semantic choice
 *       needed.</b> E.g. {@code Expense} has both
 *       {@code costCenterDeptId} and {@code creatorDeptId}. Only one
 *       defines row visibility for permissions; the framework can't
 *       pick.</li>
 *   <li><b>Non-standard anchor field name.</b> The default expects literal
 *       {@code departmentId} / {@code employeeId}.</li>
 *   <li><b>Indirect path through a non-Employee bridge.</b> The default
 *       only knows direct and via-{@code employeeId} hop shapes.</li>
 * </ol>
 *
 * <h3>Override skeleton</h3>
 * <pre>
 * &#64;Component
 * public class MyDeptAnchorResolver implements DepartmentCascadePathResolver {
 *     &#64;Override
 *     public Optional&lt;String&gt; resolve(String modelName) {
 *         return switch (modelName) {
 *             case "Expense"  -&gt; Optional.of("costCenterDeptId");
 *             case "Project"  -&gt; Optional.of("ownerEmployeeId.departmentId");
 *             default         -&gt; standardResolve(modelName);
 *         };
 *     }
 * }
 * </pre>
 */
public interface DepartmentCascadePathResolver {

    /** The path a model that IS Department resolves to — no hop, the row is the department. */
    String SELF_PATH = "";

    /**
     * @param modelName PascalCase model name being queried
     *                  (e.g. {@code "LeaveRequest"})
     * @return the dot-path leading to Department on this model (without
     *         the trailing {@code ".idPath"}), or {@link #SELF_PATH} when the model
     *         is Department itself; {@code Optional.empty()}
     *         when no path can be resolved
     */
    Optional<String> resolve(String modelName);

    /**
     * The field a department scope filters on, given a resolved path.
     *
     * <p>A name for {@link IdPath#fieldOn} in this interface's own terms: {@link #SELF_PATH} is
     * how a resolver says "the model is Department itself", and that is the one case the join has
     * to leave alone.
     */
    static String idPathField(String cascadePath) {
        return IdPath.fieldOn(cascadePath);
    }
}
