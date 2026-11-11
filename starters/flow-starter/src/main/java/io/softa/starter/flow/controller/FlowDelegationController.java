package io.softa.starter.flow.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.dto.FlowDelegationFlowView;
import io.softa.starter.flow.dto.FlowDelegationSearchRequest;
import io.softa.starter.flow.dto.FlowDelegationView;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.service.FlowDelegationService;
import io.softa.starter.flow.service.support.view.FlowDelegationViews;

import static io.softa.starter.flow.service.support.FlowActors.currentUserId;

/**
 * REST endpoints for managing delegation rules — each user's own.
 *
 * <p>The reads and the cancel carry {@link SkipPermissionCheck}: they are pinned to the caller (the search
 * by its box, the cancel by its ownership check), and {@code FlowDelegation} is granted to no business role, so
 * row scope could only empty the lists and turn a cancel into a silent no-op. The flows a rule can be scoped
 * to are the tenant's, whoever asks — an employee's row scope on the flow models would leave them none.
 * Creating needs no waiver.
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
    @Operation(summary = "Create delegation rule", description = "Creates a new delegation rule, in force over its "
            + "period: from its start time, or its creation without one, until its end time or its cancellation. "
            + "The delegator is resolved from the login context; the delegate must be someone else whose account can "
            + "act, a rule with both times ends after it starts, and a rule of scope Flow or Node names one of the "
            + "flows (and steps) GET /flow/delegations/flows lists.")
    public ApiResponse<FlowDelegation> create(@RequestBody FlowDelegation delegation) {
        delegation.setDelegatorId(currentUserId());
        return ApiResponse.success(delegationService.createDelegation(delegation));
    }

    @PostMapping("/search")
    @Operation(summary = "Search the current user's delegations",
            description = "One page of the caller's own delegation rules in a box — Mine: the rules routing their "
                    + "tasks, whatever their status; ToMe: the rules routing others' tasks to them, now or later "
                    + "(neither cancelled nor ended) — newest first. filters (nested AND / OR) and orders may name "
                    + "only stored fields of FlowDelegation, else 400; the filters are ANDed onto the caller and the "
                    + "box, never replacing them, and orders replace the newest-first order. Each rule comes with "
                    + "both people named and its status now: Scheduled, Active, Ended or Cancelled.")
    @SkipPermissionCheck
    public ApiResponse<Page<FlowDelegationView>> searchDelegations(@RequestBody FlowDelegationSearchRequest request) {
        return ApiResponse.success(views.of(delegationService.searchDelegations(currentUserId(), request)));
    }

    @GetMapping("/flows")
    @Operation(summary = "Flows to delegate", description = "The approval flows a delegation rule can be scoped to: "
            + "every enabled Approval flow of the caller's tenant with an effective revision, with that revision's "
            + "approval steps (for scope Node), sorted by flow name.")
    @SkipPermissionCheck
    public ApiResponse<List<FlowDelegationFlowView>> delegableFlows() {
        return ApiResponse.success(delegationService.listDelegableFlows());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel delegation", description = "Deactivates one of the current user's delegation rules.")
    @SkipPermissionCheck
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        delegationService.cancelDelegation(id, currentUserId());
        return ApiResponse.success(null);
    }
}
