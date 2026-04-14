package io.softa.starter.message.inbox.service.impl;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.inbox.entity.InboxTodo;
import io.softa.starter.message.inbox.enums.TodoStatus;
import io.softa.starter.message.inbox.service.InboxTodoService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Implementation of {@link InboxTodoService}.
 */
@Service
public class InboxTodoServiceImpl extends EntityServiceImpl<InboxTodo, Long>
        implements InboxTodoService {

    @Override
    public void complete(Long id) {
        InboxTodo todo = getById(id)
                .orElseThrow(() -> new IllegalArgumentException("Todo {0} not found.", id));
        todo.setStatus(TodoStatus.DONE);
        todo.setCompletedAt(LocalDateTime.now());
        updateOne(todo);
    }

    @Override
    public void reject(Long id) {
        InboxTodo todo = getById(id)
                .orElseThrow(() -> new IllegalArgumentException("Todo {0} not found.", id));
        todo.setStatus(TodoStatus.REJECTED);
        todo.setCompletedAt(LocalDateTime.now());
        updateOne(todo);
    }

    @Override
    public void cancel(Long id) {
        InboxTodo todo = getById(id)
                .orElseThrow(() -> new IllegalArgumentException("Todo {0} not found.", id));
        todo.setStatus(TodoStatus.CANCELLED);
        todo.setCompletedAt(LocalDateTime.now());
        updateOne(todo);
    }

    @Override
    public int cancelBySource(String sourceType, Long sourceId) {
        List<InboxTodo> todos = searchList(new FlexQuery(new Filters()
                .eq(InboxTodo::getSourceType, sourceType)
                .eq(InboxTodo::getSourceId, sourceId)
                .eq(InboxTodo::getStatus, TodoStatus.PENDING)));
        LocalDateTime now = LocalDateTime.now();
        for (InboxTodo todo : todos) {
            todo.setStatus(TodoStatus.CANCELLED);
            todo.setCompletedAt(now);
            updateOne(todo);
        }
        return todos.size();
    }

    @Override
    public List<InboxTodo> findBySource(String sourceType, Long sourceId) {
        return searchList(new FlexQuery(new Filters()
                .eq(InboxTodo::getSourceType, sourceType)
                .eq(InboxTodo::getSourceId, sourceId)));
    }

    @Override
    public int countPending(Long assigneeId) {
        return (int) count(new Filters()
                .eq(InboxTodo::getAssigneeId, assigneeId)
                .eq(InboxTodo::getStatus, TodoStatus.PENDING));
    }
}
