package io.softa.starter.user.controller;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.EntityService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.dto.BulkUpdateParams;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.service.SystemRoleWriteGuard;

/**
 * Base controller for the four models that carry a built-in role's definition and access —
 * {@code Role}, {@code RoleNavigation}, {@code RoleDataScope}, {@code RoleSensitiveFieldSet}. Every
 * write verb is declared here so it is checked by {@link SystemRoleWriteGuard} before reaching
 * {@link ModelService}.
 *
 * <h3>Why the verbs are declared rather than inherited</h3>
 * {@code EntityController} carries no endpoints: a typed controller serves only the verbs it declares
 * itself, and everything else is served by the generic controller mapped at {@code /{modelName}}.
 * Declaring a verb here shadows the generic route for these models (a literal path beats a template),
 * which is what puts the guard in front of it — an undeclared verb would simply keep going to the
 * generic controller, unguarded.
 *
 * <p>What the guard enforces — see {@link SystemRoleWriteGuard} for the full rules — is that a role's
 * {@code code} never changes, a built-in role is never deleted or copied, and the two administrator
 * roles, whose access is computed at runtime, accept no edit. Other built-in roles are open to the
 * same edits as a role the tenant created. Without these declared verbs, the generic route would reach
 * all four models with none of that checked.
 *
 * <h3>⚠️ This list must track the framework</h3>
 * These are the 15 write verbs {@code ModelController} exposes today. <b>When the framework adds one,
 * it must be added here too</b> — otherwise it reaches these models through the generic route with no
 * guard, and the miss is silent: indistinguishable from a deliberate exemption. The
 * {@code SystemRoleWriteGuardCoverageTest} compares this class against {@code ModelController} by
 * reflection and fails when the two drift apart, so the reminder is enforced rather than hoped for.
 *
 * <p>Membership is deliberately not here: {@code UserRoleRel} keeps its own plain controller, because
 * assigning and unassigning users is the point of a built-in role, not a modification of it.
 */
public abstract class SystemRoleGuardedController<S extends EntityService<T, K>,
        T extends AbstractModel, K extends Serializable> extends EntityController<S, T, K> {

    @Autowired
    protected ModelService<K> modelService;

    @Autowired
    protected SystemRoleWriteGuard writeGuard;

    /*
     * Why the mapped methods below read their dependencies through these two accessors instead of
     * touching the fields directly.
     *
     * Every concrete subclass is a @RestController, and ApiExceptionAspect advises
     * `@within(@RestController)` — so Spring wraps each of them in a CGLIB proxy. CGLIB proxies by
     * subclassing, and a `final` method cannot be overridden: a call to one lands on the proxy
     * instance and runs the body *there*. Spring injects the target, never the proxy, so a field read
     * in such a body is null. That is not hypothetical — it answered 500 on every guarded write verb
     * with `Cannot invoke "SystemRoleWriteGuard.guardByIds(...)" because "this.writeGuard" is null`.
     *
     * These accessors are deliberately neither final nor private: CGLIB can override neither, and a
     * private one reproduces the very bug it is meant to fix. Being overridable is the mechanism —
     * calling one from a final body dispatches through the proxy to the target, which returns the
     * injected instance. The `final` on the mapped methods keeps doing what it was for: a subclass
     * changes WHAT a write does through the doXxx hooks, and cannot drop the guard by overriding the
     * mapped method. What it can no longer prevent on its own is a subclass substituting these two,
     * so GuardedControllerUnderProxyTest asserts that none of them does.
     *
     * A new mapped method must use these too. GuardedControllerUnderProxyTest drives every verb
     * through a real CGLIB proxy, so one that reads a field directly fails there rather than in
     * production.
     */

    protected SystemRoleWriteGuard guard() {
        return writeGuard;
    }

    protected ModelService<K> models() {
        return modelService;
    }

    /** The model these endpoints write — the concrete controller names it once. */
    protected abstract String modelName();

    // ─────────────────────── create ───────────────────────

    @PostMapping("/createOne")
    public final ApiResponse<K> createOne(@RequestBody Map<String, Object> row) {
        guard().guardCreate(modelName(), List.of(row));
        return ApiResponse.success(models().createOne(modelName(), row));
    }

    @PostMapping("/createOneAndFetch")
    public final ApiResponse<Map<String, Object>> createOneAndFetch(@RequestBody Map<String, Object> row) {
        guard().guardCreate(modelName(), List.of(row));
        return ApiResponse.success(models().createOneAndFetch(modelName(), row, ConvertType.REFERENCE));
    }

    @PostMapping("/createList")
    public final ApiResponse<List<K>> createList(@RequestBody List<Map<String, Object>> rows) {
        guard().guardCreate(modelName(), rows);
        return ApiResponse.success(models().createList(modelName(), rows));
    }

    @PostMapping("/createListAndFetch")
    public final ApiResponse<List<Map<String, Object>>> createListAndFetch(@RequestBody List<Map<String, Object>> rows) {
        guard().guardCreate(modelName(), rows);
        return ApiResponse.success(models().createListAndFetch(modelName(), rows, ConvertType.REFERENCE));
    }

    // ─────────────────────── update ───────────────────────

    @PostMapping("/updateOne")
    public final ApiResponse<Boolean> updateOne(@RequestBody Map<String, Object> row) {
        guard().guardUpdate(modelName(), List.of(row));
        return ApiResponse.success(models().updateOne(modelName(), row));
    }

    @PostMapping("/updateOneAndFetch")
    public final ApiResponse<Map<String, Object>> updateOneAndFetch(@RequestBody Map<String, Object> row) {
        guard().guardUpdate(modelName(), List.of(row));
        return ApiResponse.success(models().updateOneAndFetch(modelName(), row, ConvertType.REFERENCE));
    }

    @PostMapping("/updateList")
    public final ApiResponse<Boolean> updateList(@RequestBody List<Map<String, Object>> rows) {
        guard().guardUpdate(modelName(), rows);
        return ApiResponse.success(models().updateList(modelName(), rows));
    }

    @PostMapping("/updateListAndFetch")
    public final ApiResponse<List<Map<String, Object>>> updateListAndFetch(@RequestBody List<Map<String, Object>> rows) {
        guard().guardUpdate(modelName(), rows);
        return ApiResponse.success(models().updateListAndFetch(modelName(), rows, ConvertType.REFERENCE));
    }

    @PostMapping("/updateByFilter")
    public final ApiResponse<Integer> updateByFilter(@RequestBody BulkUpdateParams bulkUpdateParams) {
        // Both sides: the rows the filter selects AND the role the new values would move them to.
        guard().guardUpdateByFilter(modelName(), bulkUpdateParams.getFilters(), bulkUpdateParams.getValues());
        return ApiResponse.success(models().updateByFilter(
                modelName(), bulkUpdateParams.getFilters(), bulkUpdateParams.getValues()));
    }

    // ─────────────────────── delete / copy ───────────────────────

    @PostMapping("/deleteById")
    public final ApiResponse<Boolean> deleteById(@RequestParam K id) {
        guard().guardByIds(modelName(), List.of(id));
        return ApiResponse.success(doDeleteById(id));
    }

    @PostMapping("/deleteByIds")
    public final ApiResponse<Boolean> deleteByIds(@RequestParam List<K> ids) {
        guard().guardByIds(modelName(), ids);
        return ApiResponse.success(doDeleteByIds(ids));
    }

    /**
     * How the delete is actually performed. Override to route through the typed service — the grant
     * models do, because it publishes {@code RoleGrantChangedEvent} for per-role cache eviction, which
     * a straight {@code ModelService} write skips.
     *
     * <p>The endpoint methods above are {@code final} on purpose: the guard call and the behaviour are
     * separated so a subclass can change WHAT the write does without being able to remove the check by
     * overriding the mapped method and forgetting it.
     */
    protected boolean doDeleteById(K id) {
        return modelService.deleteById(modelName(), id);
    }

    protected boolean doDeleteByIds(List<K> ids) {
        return modelService.deleteByIds(modelName(), ids);
    }

    @PostMapping("/copyById")
    public final ApiResponse<K> copyById(@RequestParam K id) {
        guard().guardByIds(modelName(), List.of(id));
        return ApiResponse.success(models().copyById(modelName(), id));
    }

    @PostMapping("/copyByIdAndFetch")
    public final ApiResponse<Map<String, Object>> copyByIdAndFetch(@RequestParam K id) {
        guard().guardByIds(modelName(), List.of(id));
        return ApiResponse.success(models().copyByIdAndFetch(modelName(), id, ConvertType.REFERENCE));
    }

    @PostMapping("/copyByIds")
    public final ApiResponse<List<K>> copyByIds(@RequestParam List<K> ids) {
        guard().guardByIds(modelName(), ids);
        return ApiResponse.success(models().copyByIds(modelName(), ids));
    }

    @PostMapping("/copyByIdsAndFetch")
    public final ApiResponse<List<Map<String, Object>>> copyByIdsAndFetch(@RequestParam List<K> ids) {
        guard().guardByIds(modelName(), ids);
        return ApiResponse.success(models().copyByIdsAndFetch(modelName(), ids, ConvertType.REFERENCE));
    }
}
