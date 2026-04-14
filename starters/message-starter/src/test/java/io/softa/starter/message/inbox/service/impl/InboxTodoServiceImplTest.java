package io.softa.starter.message.inbox.service.impl;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.message.inbox.entity.InboxTodo;
import io.softa.starter.message.inbox.enums.TodoStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class InboxTodoServiceImplTest {

    private InboxTodoServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new InboxTodoServiceImpl());
    }

    // ========== complete Tests ==========

    @Test
    void completeSetsDoneStatusAndCompletedAt() {
        InboxTodo todo = new InboxTodo();
        todo.setId(1L);
        todo.setStatus(TodoStatus.PENDING);

        doReturn(Optional.of(todo)).when(service).getById(1L);
        doReturn(true).when(service).updateOne(any());

        service.complete(1L);

        Assertions.assertEquals(TodoStatus.DONE, todo.getStatus());
        Assertions.assertNotNull(todo.getCompletedAt());
        verify(service).updateOne(todo);
    }

    @Test
    void completeThrowsForNonexistentTodo() {
        doReturn(Optional.empty()).when(service).getById(99L);

        Assertions.assertThrows(Exception.class, () -> service.complete(99L));
        verify(service, never()).updateOne(any());
    }

    // ========== reject Tests ==========

    @Test
    void rejectSetsRejectedStatusAndCompletedAt() {
        InboxTodo todo = new InboxTodo();
        todo.setId(2L);
        todo.setStatus(TodoStatus.PENDING);

        doReturn(Optional.of(todo)).when(service).getById(2L);
        doReturn(true).when(service).updateOne(any());

        service.reject(2L);

        Assertions.assertEquals(TodoStatus.REJECTED, todo.getStatus());
        Assertions.assertNotNull(todo.getCompletedAt());
        verify(service).updateOne(todo);
    }

    @Test
    void rejectThrowsForNonexistentTodo() {
        doReturn(Optional.empty()).when(service).getById(99L);

        Assertions.assertThrows(Exception.class, () -> service.reject(99L));
        verify(service, never()).updateOne(any());
    }

    @Test
    void completedAtTimestampIsSetOnComplete() {
        InboxTodo todo = new InboxTodo();
        todo.setId(3L);
        todo.setCompletedAt(null);

        doReturn(Optional.of(todo)).when(service).getById(3L);
        doReturn(true).when(service).updateOne(any());

        service.complete(3L);

        Assertions.assertNotNull(todo.getCompletedAt());
    }

    @Test
    void completedAtTimestampIsSetOnReject() {
        InboxTodo todo = new InboxTodo();
        todo.setId(4L);
        todo.setCompletedAt(null);

        doReturn(Optional.of(todo)).when(service).getById(4L);
        doReturn(true).when(service).updateOne(any());

        service.reject(4L);

        Assertions.assertNotNull(todo.getCompletedAt());
    }

    // ========== countPending Tests ==========

    @Test
    void countPendingReturnsResultFromCount() {
        doReturn(3L).when(service).count(any(Filters.class));

        int result = service.countPending(10L);

        Assertions.assertEquals(3, result);
    }

    @Test
    void countPendingReturnsZeroWhenNone() {
        doReturn(0L).when(service).count(any(Filters.class));

        int result = service.countPending(10L);

        Assertions.assertEquals(0, result);
    }
}
