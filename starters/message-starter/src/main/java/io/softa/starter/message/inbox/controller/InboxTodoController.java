package io.softa.starter.message.inbox.controller;

import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.inbox.entity.InboxTodo;
import io.softa.starter.message.inbox.service.InboxTodoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for inbox todo items.
 */
@Tag(name = "InboxTodo")
@RestController
@RequestMapping("/InboxTodo")
public class InboxTodoController
        extends EntityController<InboxTodoService, InboxTodo, Long> {

    @Autowired
    private InboxTodoService inboxTodoService;

    @Operation(summary = "Mark a todo as done")
    @PostMapping("/complete")
    public ApiResponse<Void> complete(@RequestParam Long id) {
        inboxTodoService.complete(id);
        return ApiResponse.success();
    }

    @Operation(summary = "Mark a todo as rejected")
    @PostMapping("/reject")
    public ApiResponse<Void> reject(@RequestParam Long id) {
        inboxTodoService.reject(id);
        return ApiResponse.success();
    }

    @Operation(summary = "Count pending todos for an assignee")
    @PostMapping("/countPending")
    public ApiResponse<Integer> countPending(@RequestParam Long assigneeId) {
        return ApiResponse.success(inboxTodoService.countPending(assigneeId));
    }
}
