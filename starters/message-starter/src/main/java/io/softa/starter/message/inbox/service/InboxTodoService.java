package io.softa.starter.message.inbox.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.inbox.entity.InboxTodo;

import java.util.List;

/**
 * CRUD service for {@link InboxTodo}.
 * <p>
 * Low-level service — prefer {@link InboxService} for application use.
 */
public interface InboxTodoService extends EntityService<InboxTodo, Long> {

    /**
     * Mark a todo as done and record the completion timestamp.
     *
     * @param id todo ID
     */
    void complete(Long id);

    /**
     * Mark a todo as rejected and record the timestamp.
     *
     * @param id todo ID
     */
    void reject(Long id);

    /**
     * Mark a todo as cancelled.
     *
     * @param id todo ID
     */
    void cancel(Long id);

    /**
     * Cancel all pending todos linked to a specific source object.
     *
     * @param sourceType originating object type (e.g. "FLOW_INSTANCE")
     * @param sourceId   originating object ID
     * @return number of todos cancelled
     */
    int cancelBySource(String sourceType, Long sourceId);

    /**
     * Find all todos linked to a specific source object.
     *
     * @param sourceType originating object type
     * @param sourceId   originating object ID
     * @return list of matching todos
     */
    List<InboxTodo> findBySource(String sourceType, Long sourceId);

    /**
     * Count pending todos for an assignee.
     *
     * @param assigneeId user ID
     * @return count of todos in PENDING status
     */
    int countPending(Long assigneeId);
}
