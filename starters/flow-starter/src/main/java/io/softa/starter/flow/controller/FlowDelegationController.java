package io.softa.starter.flow.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.service.FlowDelegationService;

/**
 * REST endpoints for managing delegation rules.
 */
@Tag(name = "Flow Delegation")
@RestController
@RequestMapping("/flow/delegations")
public class FlowDelegationController {

    private final FlowDelegationService delegationService;

    public FlowDelegationController(FlowDelegationService delegationService) {
        this.delegationService = delegationService;
    }

    @PostMapping
    @Operation(summary = "Create delegation rule", description = "Creates a new delegation rule. The delegator is resolved from the login context.")
    public ApiResponse<FlowDelegation> create(@RequestBody FlowDelegation delegation) {
        delegation.setDelegatorId(currentUserId());
        return ApiResponse.success(delegationService.createDelegation(delegation));
    }

    @GetMapping("/my")
    @Operation(summary = "My delegations", description = "Lists delegation rules created by the current user.")
    public ApiResponse<List<FlowDelegation>> myDelegations() {
        return ApiResponse.success(delegationService.getMyDelegations(currentUserId()));
    }

    @GetMapping("/to-me")
    @Operation(summary = "Delegations to me", description = "Lists active delegation rules assigned to the current user.")
    public ApiResponse<List<FlowDelegation>> delegationsToMe() {
        return ApiResponse.success(delegationService.getDelegationsToMe(currentUserId()));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel delegation", description = "Deactivates a delegation rule.")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        delegationService.cancelDelegation(id);
        return ApiResponse.success(null);
    }

    private static String currentUserId() {
        Long userId = ContextHolder.getContext().getUserId();
        if (userId == null) {
            throw new FlowAuthorizationException("Authentication is required");
        }
        return userId.toString();
    }
}
