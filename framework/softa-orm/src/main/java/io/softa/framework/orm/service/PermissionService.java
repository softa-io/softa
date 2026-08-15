package io.softa.framework.orm.service;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import io.softa.framework.orm.domain.Filters;
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

    /**
     * Whether the data plane does not apply to the current caller — an administrator, or a context
     * that has already waived checks.
     *
     * <p>Exists for the few authorization decisions the framework has to make in its own code rather
     * than through {@code checkIdAccess}: the file endpoints authorize a file with no owning row
     * against its uploader, and comparing user ids by hand would deny an administrator, who by
     * definition bypasses every other data-plane check. Asking here keeps that one invariant in one
     * place instead of teaching softa-web what an admin role code looks like.
     */
    boolean isDataPlaneExempt();

    /**
     * Whether the caller holds the permission that a normal request for this model and action would
     * have been gated by.
     *
     * <p>The endpoint gate answers this for every ordinary path, by matching the request URL. The file
     * endpoints have no URL it can match — the model arrives as a request parameter, so they had to be
     * whitelisted to be reachable at all — and the row-level check they do instead cannot stand in for
     * it: {@code checkIdAccess} resolves to "is this row within your READ scope" whatever access type
     * it is handed, so a read-only caller passes an UPDATE check on any row they can see. Asking this
     * restores the missing half for those endpoints.
     *
     * <p>Answers {@code true} when nothing is registered for the model and action: an unregistered
     * pair is not a denial, it is a model no permission speaks for, and the whitelist that opened the
     * endpoint is what governs it then.
     *
     * @param model the model being written
     * @param accessType the operation being performed
     * @return true when the caller may perform it, or when no permission covers it
     */
    boolean hasModelActionGrant(String model, AccessType accessType);

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
}