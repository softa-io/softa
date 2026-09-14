package io.softa.framework.orm.service;

/**
 * Asks whether a consultant's membership may still be entered, right now.
 *
 * <p>Declared here, in the layer both sides can see, for the same reason {@link EntitlementService}
 * is: the question is asked by the permission gate and answered by user management, and those two
 * starters are deliberately independent of each other. Optional — a deployment with no consultants
 * installs no implementation and the gate skips the question.
 *
 * <p><b>Asked per request, not cached alongside the permission snapshot.</b> A consultant's access
 * ends on a DATE: nobody edits anything when a grant lapses at midnight, so an answer cached for a
 * snapshot's TTL would keep a lapsed consultant inside a customer's tenant for the rest of it. The
 * price is one read per request from a population of a handful of platform staff — the right trade
 * against a stale answer about somebody else's data.
 */
@FunctionalInterface
public interface ConsultantAccessChecker {

    /**
     * @param accountId the membership the session holds (the request's userId)
     * @return false when this consultant may no longer enter: disabled, the grant revoked, past its
     *         end date, or the tenant itself frozen. True for a membership that is not a
     *         consultant's — this is only consulted for callers already identified as consultants,
     *         and failing closed on an ordinary account would be the wrong direction to be wrong in
     *         for a question that is not about them.
     */
    boolean stillAuthorized(Long accountId);
}
