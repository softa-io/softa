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
 * whose requests to filter by, and the people and departments a flow names.
 *
 * <p>Granted with the pages whose pickers read it: the application lists these endpoints in those pages' permissions,
 * not in {@code permission.authenticated-bypass-patterns}, which would skip the endpoint gate and a consultant's
 * expiry with it. {@code /people} searches the directory model ({@code softa.flow.org.directory-model}) with the
 * caller's own row scope — a role's scope on it decides whom its holders find — and offers only people who can act,
 * without saying who cannot. {@code /people/all} is for the pages that run flows rather than take part in them, the
 * designer and the operations console, and their permissions alone list it: every account, people who can no longer
 * act and accounts without an employee record included, each saying whether it can act, past the caller's row scope.
 * The by-ids reads name the people and departments already picked, whatever the caller's row scope.
 */
@Tag(name = "Flow Directory")
@RestController
@RequestMapping("/flow/directory")
public class FlowDirectoryController {

    private final FlowDirectoryService directoryService;

    public FlowDirectoryController(FlowDirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    /** Not waived: the caller's row scope on the directory model is what decides whom they find. */
    @GetMapping("/people")
    @Operation(summary = "Search people", description = "People whose login account signs in, matching the keyword on "
            + "code or name (all when blank), by name, as the caller's roles let them read the directory. limit "
            + "defaults to 20, at most 200: a picker asks again for more as its list is scrolled.")
    public ApiResponse<List<DirectoryPerson>> searchPeople(@RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(directoryService.searchPeople(keyword, limit));
    }

    @GetMapping("/people/by-ids")
    @Operation(summary = "People by account id", description = "Names picked people, in the order asked, whatever the "
            + "caller's row scope: an employee as the search shows them, an account without an employee record by its "
            + "name alone; whether they can act is not said. Ids naming no account are left out; at most 200, "
            + "comma-separated.")
    @SkipPermissionCheck
    public ApiResponse<List<DirectoryPerson>> getPeople(@RequestParam(required = false) List<Long> ids) {
        return ApiResponse.success(directoryService.getPeople(ids));
    }

    @GetMapping("/people/all")
    @Operation(summary = "Search every account", description = "For the flow designer and the operations console: "
            + "every login account matching the keyword (all when blank) — employees on code or name, accounts without "
            + "an employee record on their own name — by name, each saying whether it can act (active). Only those that "
            + "can unless includeInactive — as when moving work off someone who left. limit defaults to 20, at most 200.")
    @SkipPermissionCheck
    public ApiResponse<List<DirectoryPerson>> searchAllPeople(@RequestParam(required = false) String keyword,
                                                              @RequestParam(defaultValue = "false") boolean includeInactive,
                                                              @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(directoryService.searchAllPeople(keyword, includeInactive, limit));
    }

    @GetMapping("/people/all/by-ids")
    @Operation(summary = "Every account by id", description = "For the flow designer and the operations console: "
            + "/people/by-ids, each saying whether it can act (active). At most 200, comma-separated.")
    @SkipPermissionCheck
    public ApiResponse<List<DirectoryPerson>> getAllPeople(@RequestParam(required = false) List<Long> ids) {
        return ApiResponse.success(directoryService.getAllPeople(ids));
    }

    @GetMapping("/departments")
    @Operation(summary = "Search departments", description = "Open departments matching the keyword on code or name "
            + "(all when blank), by name, each with its parent's name. limit defaults to 20, at most 200.")
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
