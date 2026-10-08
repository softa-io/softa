package io.softa.framework.orm.service;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.softa.framework.orm.domain.CreateAccess;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.RecordAccess;
import io.softa.framework.orm.enums.AccessType;

/**
 * Permission check service interface
 */
public interface PermissionService {

    /**
     * Check the access permission of multiple models and fields at the same time.
     * Check whether the user has access to the specified {domain: [fields]} model name and its field list.
     *
     * @param model model name
     * @param accessModelFields {modelName: Set(fields)} dictionary structure,
     *                          used to check access permissions when cascading read.
     * @param accessType operation type, default is READ, that is, check whether it has "read operation" permission
     */
    void checkModelCascadeFieldsAccess(String model, Map<String, Set<String>> accessModelFields, AccessType accessType);

    /**
     * Check the ids range and field level operation permission.
     *
     * @param model model name
     * @param ids data ids, check model and field level permissions when empty
     * @param fields field set
     * @param accessType   operation type, default is READ
     */
    void checkIdsFieldsAccess(String model, Collection<? extends Serializable> ids, Set<String> fields, AccessType accessType);

    /**
     * Model permission check
     *
     * @param model model name
     * @param accessType  access type
     */
    void checkModelAccess(String model, AccessType accessType);

    /**
     * Model fields permission check
     *
     * @param model  model name
     * @param fields field set
     * @param accessType   access type
     */
    void checkModelFieldsAccess(String model, Collection<String> fields, AccessType accessType);

    /**
     * Model data permission check
     *
     * @param model      model name
     * @param id         data ID
     * @param accessType operation type, default is READ
     */
    void checkIdAccess(String model, Serializable id, AccessType accessType);

    /**
     * Ids data range permission check.
     * When checking the ids operation permission, first query according to the permission,
     * and check whether the ids exist in the database when there is no permission.
     * If the ids exist, report no data access permission,
     * and if the ids do not exist, report that the data to be read does not exist.
     *
     * @param model model name
     * @param ids data ids, check model and field level permissions when empty
     * @param accessType operation type, default is READ
     */
    void checkIdsAccess(String model, Collection<? extends Serializable> ids, AccessType accessType);

    /**
     * Check the route access permission
     *
     * @param route route
     */
    void checkRouteAccess(String route);

    Set<String> getUserBlockedModelFields(String model, AccessType accessType);

    /**
     * Append data permission filters.
     *
     * @param model model name
     * @param originalFilters original filter conditions
     * @return merged filter conditions
     */
    Filters appendScopeAccessFilters(String model, Filters originalFilters);

    /**
     * The part of {@link #appendScopeAccessFilters} that is a REWRITE of what the caller asked for
     * rather than a restriction added on top — today, turning a subtree condition into the id-path
     * condition it means.
     *
     * <p>Always applies, to every caller: an admin, a system-level read and a relation expansion ask
     * the same question as anyone else and need the same answer. A {@code CHILD OF} left unrewritten
     * does not widen or narrow the result, it compiles to a pattern match against an id and matches
     * by coincidence.
     *
     * <p>{@link #appendScopeAccessFilters} runs this first and then adds the caller's row range on
     * top. A read that legitimately crosses that range — see
     * {@link ModelService#searchListIgnoringRowScope} — calls this one instead, so it keeps the
     * rewrite and drops only the range.
     *
     * @param model the model being queried
     * @param originalFilters the filters as assembled so far
     * @return the filters with scope-independent rewrites applied
     */
    default Filters rewriteScopeFilters(String model, Filters originalFilters) {
        return originalFilters;
    }

    /**
     * Silently drop blocked-for-{@code accessType} fields from the caller's
     * requested field set. Used inside read entry points
     * ({@code searchList}/{@code searchPage}/etc.) before the SELECT clause
     * is built, so the DB never returns blocked columns.
     *
     * <p>Complements — and should be invoked BEFORE —
     * {@link #checkModelFieldsAccess}: {@code filter} returns a sanitized
     * subset without throwing; {@code checkModelFieldsAccess} then
     * verifies the sanitized subset. Together they let users see the
     * fields they DO have access to, rather than 403-ing on any single
     * blocked field.
     *
     * <p>Default no-op returns {@code requested} unchanged.
     *
     * @param model       model name
     * @param requested   fields the caller asked for (may be empty →
     *                    "all stored fields" — implementations decide
     *                    whether to expand here or downstream)
     * @param accessType  usually {@link AccessType#READ}
     * @return the sanitized set; never null; may equal {@code requested}
     *         if nothing is blocked.
     */
    default Collection<String> filterReadableFields(String model,
                                                     Collection<String> requested,
                                                     AccessType accessType) {
        return requested;
    }

    /**
     * Mask blocked-field values on a response value in place, recursively.
     * Called at the tail of every read entry point in
     * {@code ModelServiceImpl}; complements
     * {@link #filterReadableFields} (which prevents blocked columns from
     * being SELECTed at all).
     *
     * <p>{@code filterReadableFields} + {@code checkModelFieldsAccess}
     * cover only the top-level requested projection; cascaded child
     * objects (e.g. {@code Employee.department.name} on a fetch of
     * Employee) can only be masked after the query returns because their
     * blocked-field set belongs to a different model. This method is the
     * one that recurses into nested cascade objects.
     *
     * <p>Expected to handle these shapes without unwrapping errors:
     * {@link java.util.Optional}, {@link java.util.Collection},
     * {@code io.softa.framework.orm.domain.Page}, cascade nested
     * {@code Map<String,Object>}, and pass-through for POJO / primitive
     * / null returns.
     *
     * <p>Default no-op returns the value unchanged.
     *
     * @return the (potentially masked in place) same reference — the
     *         return exists so implementations can substitute a wrapping
     *         type if they need to.
     */
    default <T> T maskResponseValue(String model, T value, AccessType accessType) {
        return value;
    }

    /**
     * Mask sensitive fields on rows of {@code model} that were not read through this service's read
     * path — the before / after values of a change log entry, say — exactly as a read would have
     * masked them. Each row is judged by its {@code id}: a row without one shows only what the caller
     * may see on every row.
     *
     * <p>Default no-op.
     *
     * @param model the model the rows belong to
     * @param rows  the rows, masked in place
     */
    default void maskRows(String model, List<Map<String, Object>> rows) {
        // no-op default
    }

    /**
     * Keep a query from learning, through its ordering or grouping, what its masking hides: a sort on
     * a sensitive field the caller cannot see on every row is dropped, and grouping or aggregating by
     * one is refused. Called on the query before it runs; may modify it.
     *
     * <p>Default no-op.
     */
    default void guardQuery(String model, io.softa.framework.orm.domain.FlexQuery flexQuery) {
        // no-op default
    }

    /**
     * Reject writes touching blocked-for-write fields in the payload map.
     * Called by every write entry point in {@code ModelServiceImpl}
     * ({@code createOne}/{@code createList}/{@code updateOne}/
     * {@code updateList}/{@code updateByFilter}/{@code createOrUpdate}/etc.)
     * before the row is written.
     *
     * <p>Complementary to {@link #checkIdsFieldsAccess} — that one takes a
     * pre-computed field-name set and existing row ids; this one takes
     * the raw payload map so implementations can also inspect cascade
     * nested writes (e.g. {@code {"department": {"name": "..."}}} on an
     * Employee update). Implementations should call
     * {@link #checkIdsFieldsAccess} first if row-level access is also
     * needed, then this method for payload field-name check.
     *
     * <p>Throw {@code PermissionException} on violation.
     *
     * <p>Default no-op — business implementations override.
     *
     * @param model    model name
     * @param payload  the write payload map — keys are field names being
     *                 written, values are the new field values (may
     *                 include cascade nested Maps for ToOne / ToMany
     *                 fields)
     */
    default void checkWritePayload(String model, Map<String, Object> payload) {
        // no-op default
    }

    /**
     * The endpoint gate's question, asked without a URL.
     *
     * <p>{@code PermissionInterceptor} resolves a request by matching its URL against the endpoint
     * index. The file upload endpoints cannot be matched that way — their model arrives as a request
     * <em>parameter</em>, so {@code /file/...} resolves to no model — and they are reachable only
     * because they are whitelisted, which means the gate never runs for them at all. They ask here
     * instead, naming the model and the action, and the same index answers via the canonical endpoint
     * for that pair.
     *
     * <p><b>Two defaults point opposite ways, and confusing them is easy.</b> The endpoint gate denies
     * an unregistered URL — an endpoint nobody wrote a permission for is unreachable. This grants an
     * unregistered model: a model whose CRUD nobody wrote a permission for is still usable, because
     * denying would break every such model at once. So a model with no permission item has its
     * attachments ungated here, and that is the deliberate cost of not breaking the majority.
     *
     * @param model the model being written
     * @param accessType the operation being performed
     * @return true when the caller may perform it, or when no permission covers it
     */
    boolean hasModelActionGrant(String model, AccessType accessType);

    /**
     * What the caller may do with each of these existing records: which sensitive sets are hidden or
     * read-only on it, and which of {@code UPDATE} / {@code DELETE} it may perform.
     *
     * <p>Default: nothing hidden, nothing read-only, and every action the caller holds on the model.
     *
     * @param model the model
     * @param ids   the records
     * @return one entry per id, in the order given
     */
    default List<RecordAccess> getRecordAccess(String model, Collection<? extends Serializable> ids) {
        Set<AccessType> actions = java.util.EnumSet.noneOf(AccessType.class);
        for (AccessType action : List.of(AccessType.UPDATE, AccessType.DELETE)) {
            if (hasModelActionGrant(model, action)) {
                actions.add(action);
            }
        }
        return ids.stream().map(id -> new RecordAccess(id, Set.of(), Set.of(), actions)).toList();
    }

    /**
     * Which sensitive sets a form creating a record of {@code model} must not show.
     *
     * <p>Default: none.
     */
    default CreateAccess getCreateAccess(String model) {
        return new CreateAccess(Set.of());
    }

}
