package io.softa.starter.permission.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom-endpoint entry guard: verify the caller's row scope on the endpoint's
 * MAIN model, then let the endpoint's internal cross-model access through.
 *
 * <p>Custom endpoints pass the endpoint gate (Layer A) but their body often
 * touches models the caller has no explicit scope for — reads come back
 * silently empty ({@code matchNone}) and id-writes are rejected. The decided
 * stance ("the endpoint is the resource") is: authorization happens ONCE at
 * the entry, against the main model; everything the endpoint does internally
 * is part of the granted business chain. This annotation is that entry check.
 *
 * <p>The enforcing aspect runs two steps, strictly in this order:
 * <ol>
 *   <li><b>Verify first</b> — {@code idParam} ids must ALL fall inside the
 *       caller's scope on the main model ({@code checkIdsAccess}, throws on
 *       any out-of-scope id); {@code filterParam} arguments are REWRITTEN with
 *       the caller's scope filters AND-ed in ({@code appendScopeAccessFilters},
 *       silently narrows — query semantics, mirrors the standard endpoints).</li>
 *   <li><b>Then bypass</b> — only after the check passes, the narrow
 *       {@code Context.skipDataScope} flag opens for the rest of the call, so
 *       internal reads/writes of OTHER models are no longer emptied/blocked by
 *       row scope. Field-level guards (sensitive masking, write payload) stay
 *       active. The flag is restored in a finally block.</li>
 * </ol>
 *
 * <p>{@code model} may be omitted when the controller's class-level request
 * mapping starts with the model name ({@code /LeaveRequest/...}); aggregate
 * controllers with no model in the path MUST declare it explicitly — this is
 * validated at startup, not at request time.
 *
 * <p>Repeatable: stack one per model when the endpoint spans several
 * ({@code @RequireModel(model="LeaveRequest", idParam="reqId")} +
 * {@code @RequireModel(model="Employee", idParam="empId")}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(RequireModels.class)
public @interface RequireModel {

    /**
     * Main model name. Empty = infer from the controller's class-level request
     * mapping ({@code /LeaveRequest} → {@code LeaveRequest}). Startup fails
     * loud when empty and not inferable.
     */
    String model() default "";

    /**
     * Name of the method parameter carrying the main-model id(s) — a single
     * {@code Serializable}, a {@code Collection} of them, or an array; or,
     * when {@link #idPath()} is set, the request-body DTO to navigate into.
     * Command semantics: every id must be inside the caller's scope, one
     * out-of-scope id rejects the whole call. Empty = no id check.
     */
    String idParam() default "";

    /**
     * Property path from the {@link #idParam()} parameter to the id(s), for
     * endpoints whose ids ride inside a request-body DTO:
     *
     * <pre>{@code
     * @RequireModel(model = "Employee", idParam = "request",
     *                 idPath = "documents[].employeeId")
     * public ... initiate(@RequestBody InitiateDocumentSigningRequest request)
     * }</pre>
     *
     * <p>Grammar is deliberately tiny: {@code .} steps into a property,
     * a {@code []} suffix expands a {@code Collection} element-wise. No
     * conditions, no indexes, no method calls — a shape this can't express
     * is a controller signature that should change. Not SpEL: the path is
     * compiled against the DTO's TYPES at startup, so a typo'd segment, a
     * {@code []} on a non-collection, a raw collection, or a non-id leaf
     * fails the boot, never the first request.
     *
     * <p>Extraction is fail-closed: a {@code null} anywhere along the path
     * rejects (a row whose id is missing cannot be scope-verified — skipping
     * it would reopen the omit-the-id bypass). Extracted ids are de-duplicated
     * before the check ({@code checkIdsAccess} compares against the raw list
     * size, so duplicates would false-reject legitimate calls).
     */
    String idPath() default "";

    /**
     * Name of the method parameter carrying a {@link io.softa.framework.orm.domain.Filters}
     * query. Query semantics: the argument is REPLACED by itself AND-ed with
     * the caller's scope filters, so results silently narrow instead of
     * erroring — same behavior as the standard search endpoints. Empty = no
     * filter rewrite. Only framework {@code Filters} parameters can be
     * rewritten; ad-hoc DTO/Map conditions cannot (fall back to manual
     * filtering in the service).
     */
    String filterParam() default "";

    // Deliberately NO accessType attribute. Row scope carries no read/write
    // direction (one role_data_scope row per role x model), so an access type
    // could never change what passes — it would only decorate the rejection
    // message while READING as directional enforcement. A parameter that
    // suggests control it doesn't have is worse than none.
}
