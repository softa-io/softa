package io.softa.framework.orm.sequence;

import java.util.List;

import io.softa.framework.orm.sequence.exception.SequenceCrossTenantException;
import io.softa.framework.orm.sequence.exception.SequenceNotFoundException;
import io.softa.framework.orm.sequence.exception.SequenceTimeoutException;

/**
 * Sequence allocator port. Implementation is provided by metadata-starter
 * (sequence subpackage). Framework callers depend on this interface only and
 * accept {@code Optional<SequenceService>} to allow apps without the starter
 * to run with no-op behaviour.
 *
 * <p>Implementation dispatches by {@code SysSequence.mode}:
 * <ul>
 *   <li>{@code NO_GAP}: counter UPDATE joins the caller's transaction
 *       ({@code Propagation.MANDATORY}); business rollback rolls back the
 *       counter — strict no-gap.</li>
 *   <li>{@code ALLOW_GAP}: counter UPDATE runs in a new transaction
 *       ({@code Propagation.REQUIRES_NEW}) and commits independently of the
 *       caller; business rollback leaves the counter advanced — gap accepted.</li>
 * </ul>
 *
 * <p>Tenant ID is taken implicitly from
 * {@code ContextHolder.getContext().getTenantId()}. Calls under a cross-tenant
 * context throw {@link SequenceCrossTenantException}.
 */
public interface SequenceService {

    /**
     * Allocate one sequence value. Must be invoked from a business
     * transaction in NO_GAP mode (MANDATORY); ALLOW_GAP mode opens its own
     * REQUIRES_NEW transaction internally.
     *
     * @param code business code, e.g. "Employee.code"
     * @return rendered sequence string, e.g. "EMP-00043"
     * @throws SequenceNotFoundException     row missing for the current tenant
     * @throws SequenceTimeoutException      row lock wait timed out
     * @throws SequenceCrossTenantException  invoked under a cross-tenant context
     */
    String next(String code);

    /**
     * Allocate {@code count} sequence values atomically: the underlying
     * UPDATE acquires the row lock once. In NO_GAP mode the entire batch
     * follows the caller's transaction; in ALLOW_GAP mode the entire batch
     * commits in a single inner transaction (so business rollback drops all
     * {@code count} numbers together).
     *
     * @param code  business code
     * @param count number of values to allocate; must be positive
     * @return rendered sequence strings in allocation order
     */
    List<String> nextBatch(String code, int count);

    /**
     * Read-only preview of the next sequence value without consuming it.
     * Safe to call outside a transaction. The returned value is not reserved
     * — a subsequent {@link #next(String)} from another caller may return the
     * same number.
     *
     * @param code business code
     * @return preview snapshot (formatted value + raw number + advisory note)
     */
    SequencePreview peek(String code);
}
