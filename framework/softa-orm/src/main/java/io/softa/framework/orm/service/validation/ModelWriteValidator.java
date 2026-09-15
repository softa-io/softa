package io.softa.framework.orm.service.validation;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import io.softa.framework.orm.enums.AccessType;

/**
 * The code-layer counterpart of the field constraints: a business rule the metadata cannot express —
 * it needs a query, another row, external configuration, a collection, or a permission — declared
 * once and applied at the <b>write roots</b> ({@code ModelServiceImpl.createList / updateList /
 * deleteByIds}), where every write path converges: the generic REST endpoints, custom endpoints,
 * Excel import, flow write nodes, seed loading and a direct {@code service.createOne}.
 *
 * <p>That placement is the whole point. A rule on a controller covers one endpoint; a rule on an
 * overridden {@code EntityService} method is skipped by the generic write; both leave the import and
 * the direct call open. Implementations are Spring beans — the framework collects them, orders them
 * by {@code @Order} and calls those whose {@link #supports} answers true; business code never
 * invokes a validator by name.
 *
 * <p><b>Order bands</b> (every implementation carries {@code @Order}; a missing one is logged at boot
 * and sorts last):
 * <ul>
 *   <li>0–99 preconditions: the referenced row exists, the state allows a write — fail fast with
 *       {@link WriteContext#fail}, so later validators do not dereference what is not there;</li>
 *   <li>100–199 field and configuration rules — accumulate with {@link WriteContext#reject};</li>
 *   <li>200–299 collection and cross-row rules;</li>
 *   <li>300+ batch-wide and expensive external lookups (check {@link WriteContext#hasErrors()} first
 *       when the lookup is not worth paying for a row already rejected).</li>
 * </ul>
 * Within one validator the batch method runs first, then the rows; then the next validator. Rejections
 * accumulate across validators and rows and are thrown once, as a {@link WriteValidationException}
 * carrying every field error.
 *
 * <p>Values are the caller's, <b>before</b> the field-processor pipeline coerces them — the same shape
 * the existing save gates read. A validator checks its own rule and leaves "is this a valid date" to
 * the pipeline that runs after it. On update the context carries the patch merged onto the stored
 * row, the stored row itself and the id, so "may omit but may not clear" and "exclude myself from the
 * duplicate check" need no extra query.
 */
public interface ModelWriteValidator {

    /** Whether this validator applies to the model; called once per write, not per row. */
    boolean supports(String modelName);

    /** One row about to be created: {@link WriteContext#row()} is the request row. */
    default void validateCreate(WriteContext ctx) {}

    /**
     * One row about to be updated: {@link WriteContext#row()} is the merged row,
     * {@link WriteContext#originalRow()} the stored one, {@link WriteContext#id()} its id.
     */
    default void validateUpdate(WriteContext ctx) {}

    /** Rows about to be deleted, by id. Reject by throwing. */
    default void validateDelete(String modelName, List<? extends Serializable> ids) {}

    /**
     * The whole batch before the rows are visited one by one — for rules between rows of the same
     * request (a duplicate code within one import). Reject by throwing.
     */
    default void validateBatch(String modelName, List<Map<String, Object>> rows, AccessType accessType) {}
}
