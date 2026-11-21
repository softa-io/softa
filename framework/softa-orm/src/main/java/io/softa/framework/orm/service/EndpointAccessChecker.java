package io.softa.framework.orm.service;

import java.util.Collection;
import java.util.Set;

/**
 * Whether OTHER users of the current tenant could call an endpoint — asked by code that hands work to
 * someone, so that it is not handed to a person the permission gate would then refuse: an approval
 * transferred to someone who cannot approve is stuck with them.
 *
 * <p>Answered by the permission module, by the same rules its gate applies to a request (the admin
 * principals pass; anyone else needs a granted permission that lists the endpoint). An application
 * without one has no such gate, and every user could call every endpoint.
 */
public interface EndpointAccessChecker {

    /**
     * @param userIds the users (login account ids) in question, of the current tenant
     * @param method  the HTTP method, such as {@code POST}
     * @param path    the endpoint's path inside the application, such as {@code /flow/instances/approve}
     * @return those of {@code userIds} who could not call it; empty when all could
     */
    Set<Long> withoutAccess(Collection<Long> userIds, String method, String path);
}
