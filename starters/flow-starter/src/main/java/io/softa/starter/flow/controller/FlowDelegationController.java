package io.softa.starter.flow.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowDelegationView;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.service.FlowDelegationService;
import io.softa.starter.flow.service.support.view.FlowDelegationViews;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * REST endpoints for managing delegation rules — each user's own.
 *
 * <p>The reads and the cancel carry {@link SkipPermissionCheck}: they are pinned to the caller (the
 * cancel by its ownership check), and {@code FlowDelegation} is granted to no business role, so row
 * scope could only empty the lists and turn a cancel into a silent no-op. Creating needs no waiver.
 */
@Tag(name = "Flow Delegation")
@RestController
@RequestMapping("/flow/delegations")
public class FlowDelegationController {

    private final FlowDelegationService delegationService;
    private final FlowDelegationViews views;

    public FlowDelegationController(FlowDelegationService delegationService, FlowDelegationViews views) {
        this.delegationService = delegationService;
        this.views = views;
    }

    @PostMapping
    @Operation(summary = "Create delegation rule", description = "Creates a new delegation rule, in force from its "
            + "creation. The delegator is resolved from the login context; the delegate must be someone else, and a "
            + "rule with both times ends after it starts.")
    public ApiResponse<FlowDelegation> create(@RequestBody FlowDelegation delegation) {
        delegation.setDelegatorId(currentUserId());
        return ApiResponse.success(delegationService.createDelegation(delegation));
    }

    @GetMapping("/my")
    @Operation(summary = "My delegations", description = "Lists delegation rules created by the current user.")
    @SkipPermissionCheck
    public ApiResponse<List<FlowDelegationView>> myDelegations() {
        return ApiResponse.success(views.of(delegationService.getMyDelegations(currentUserId())));
    }

    @GetMapping("/to-me")
    @Operation(summary = "Delegations to me", description = "Lists active delegation rules assigned to the current user.")
    @SkipPermissionCheck
    public ApiResponse<List<FlowDelegationView>> delegationsToMe() {
        return ApiResponse.success(views.of(delegationService.getDelegationsToMe(currentUserId())));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel delegation", description = "Deactivates one of the current user's delegation rules.")
    @SkipPermissionCheck
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        delegationService.cancelDelegation(id, currentUserId());
        return ApiResponse.success(null);
    }
}
