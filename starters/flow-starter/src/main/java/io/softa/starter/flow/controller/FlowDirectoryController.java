package io.softa.starter.flow.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.runtime.spi.DirectoryDepartment;
import io.softa.starter.flow.runtime.spi.DirectoryPerson;
import io.softa.starter.flow.service.FlowDirectoryService;

/**
 * The directory behind the people and department pickers: who to transfer to, add as a signer, CC or delegate to,
 * whose requests to filter by, and the departments a flow names.
 *
 * <p>Granted with the pages whose pickers read it: the application lists these endpoints in those pages' permissions,
 * not in {@code permission.authenticated-bypass-patterns}, which would skip the endpoint gate and a consultant's
 * expiry with it. An employee's row scope on the employee model would leave them only themselves to pick, and opening
 * that model to them would open every HR field with it; this shows a name, an employee code, a department and a
 * position, and only of people with a login account. Read past the caller's row scope, the tenant still applying.
 */
@Tag(name = "Flow Directory")
@RestController
@RequestMapping("/flow/directory")
public class FlowDirectoryController {

    private final FlowDirectoryService directoryService;

    public FlowDirectoryController(FlowDirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    @GetMapping("/people")
    @Operation(summary = "Search people", description = "Employees with a login account matching the keyword on code "
            + "or name (all when blank), by name. Only accounts that sign in, unless includeInactive — as when moving work "
            + "off someone who left. limit defaults to 20, at most 50.")
    @SkipPermissionCheck
    public ApiResponse<List<DirectoryPerson>> searchPeople(@RequestParam(required = false) String keyword,
                                                           @RequestParam(defaultValue = "false") boolean includeInactive,
                                                           @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(directoryService.searchPeople(keyword, includeInactive, limit));
    }

    @GetMapping("/people/by-ids")
    @Operation(summary = "People by account id", description = "Names picked people, in the order asked, whether they "
            + "can act or not (active says): an employee as the search shows them, an account without an employee "
            + "record by its name alone. Ids naming no account are left out; at most 200, comma-separated.")
    @SkipPermissionCheck
    public ApiResponse<List<DirectoryPerson>> getPeople(@RequestParam(required = false) List<Long> ids) {
        return ApiResponse.success(directoryService.getPeople(ids));
    }

    @GetMapping("/departments")
    @Operation(summary = "Search departments", description = "Open departments matching the keyword on code or name "
            + "(all when blank), by name, each with its parent's name. limit defaults to 20, at most 50.")
    @SkipPermissionCheck
    public ApiResponse<List<DirectoryDepartment>> searchDepartments(@RequestParam(required = false) String keyword,
                                                                    @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(directoryService.searchDepartments(keyword, limit));
    }

    @GetMapping("/departments/by-ids")
    @Operation(summary = "Departments by id", description = "Names picked departments, closed ones included, in the "
            + "order asked; at most 200, comma-separated.")
    @SkipPermissionCheck
    public ApiResponse<List<DirectoryDepartment>> getDepartments(@RequestParam(required = false) List<Long> ids) {
        return ApiResponse.success(directoryService.getDepartments(ids));
    }
}
