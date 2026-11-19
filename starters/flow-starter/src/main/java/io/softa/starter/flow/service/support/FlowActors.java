package io.softa.starter.flow.service.support;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.EmpInfo;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;

/**
 * The current actor of a user-facing flow call, resolved from the login context. The REST
 * controllers and the in-process {@code FlowClient} both take it from here, so neither reads an
 * actor or initiator id from the request.
 */
public final class FlowActors {

    private FlowActors() {}

    /**
     * The authenticated user's id as a flow actor id.
     *
     * @throws FlowAuthorizationException when the context carries no user
     */
    public static String currentUserId() {
        Long userId = ContextHolder.getContext().getUserId();
        if (userId == null) {
            throw new FlowAuthorizationException("Authentication is required");
        }
        return userId.toString();
    }

    /**
     * The authenticated user's employee, as the login context resolved it; {@code null} for an account that is
     * no employee. What a record approval's {@code subjectId} names: the employee the record is about.
     */
    public static Long currentEmployeeId() {
        EmpInfo empInfo = ContextHolder.existContext() ? ContextHolder.getContext().getEmpInfo() : null;
        return empInfo == null ? null : empInfo.getEmpId();
    }
}
