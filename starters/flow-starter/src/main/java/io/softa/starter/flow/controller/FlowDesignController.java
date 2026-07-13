package io.softa.starter.flow.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.web.controller.EntityController;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.dto.FlowBundleSummaryView;
import io.softa.starter.flow.dto.FlowDesignCreateRequest;
import io.softa.starter.flow.dto.FlowDesignDuplicateRequest;
import io.softa.starter.flow.dto.FlowDesignSaveRequest;
import io.softa.starter.flow.dto.FlowDesignStatusView;
import io.softa.starter.flow.dto.FlowPublishRequest;
import io.softa.starter.flow.dto.FlowValidationResult;
import io.softa.starter.flow.dto.FlowVariableView;
import io.softa.starter.flow.entity.FlowDebugHistory;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.FlowLaunchResponse;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.service.FlowDebugHistoryService;
import io.softa.starter.flow.service.FlowDesignService;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.FlowPublishService;
import io.softa.starter.flow.service.FlowVariableCatalogService;

/**
 * Dedicated REST surface for the graphical flow editor (react-flow frontend).
 *
 * <p>The generic model API at {@code /FlowDesign/**} remains available as the
 * platform data plane and is intentionally not claimed or intercepted; the
 * editor exclusively uses this surface.</p>
 */
@Tag(name = "Flow Designs")
@Validated
@RestController
@RequestMapping("/flow/designs")
public class FlowDesignController extends EntityController<FlowDesignService, FlowDesign, Long> {

    /** Draft list projection — the heavy designJson column is deliberately excluded. */
    private static final List<String> SUMMARY_FIELDS = List.of(
            LambdaUtils.getAttributeName(FlowDesign::getId),
            LambdaUtils.getAttributeName(FlowDesign::getFlowName),
            LambdaUtils.getAttributeName(FlowDesign::getFlowCode),
            LambdaUtils.getAttributeName(FlowDesign::getScenario),
            LambdaUtils.getAttributeName(FlowDesign::getPublishedRevision),
            LambdaUtils.getAttributeName(FlowDesign::getVersion),
            LambdaUtils.getAttributeName(FlowDesign::getCreatedTime),
            LambdaUtils.getAttributeName(FlowDesign::getUpdatedTime));

    @Autowired
    private FlowPublishService flowPublishService;

    @Autowired
    private DefaultFlowCompiler flowCompiler;

    @Autowired
    private FlowVariableCatalogService variableCatalogService;

    @Autowired
    private FlowLaunchService flowLaunchService;

    @Autowired(required = false)
    private FlowDebugHistoryService debugHistoryService;

    @PostMapping
    @Operation(summary = "Create flow draft")
    public ApiResponse<FlowDesign> create(@Valid @RequestBody FlowDesignCreateRequest request) {
        return ApiResponse.success(service.createDesign(request));
    }

    @GetMapping
    @Operation(summary = "List flow drafts",
            description = "Paged draft list for the editor home. Excludes the canvas document; "
                    + "keyword matches flow name or code.")
    public ApiResponse<Page<FlowDesign>> list(@RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) FlowScenario scenario,
                                              @RequestParam(defaultValue = "1") Integer pageNumber,
                                              @RequestParam(defaultValue = "50") Integer pageSize) {
        Filters filters = new Filters();
        if (scenario != null) {
            filters.eq(FlowDesign::getScenario, scenario);
        }
        if (keyword != null && !keyword.isBlank()) {
            filters.and(Filters.or(
                    new Filters().contains(FlowDesign::getFlowName, keyword),
                    new Filters().contains(FlowDesign::getFlowCode, keyword)));
        }
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(FlowDesign::getUpdatedTime));
        query.select(SUMMARY_FIELDS);
        return ApiResponse.success(service.searchPage(query, Page.of(pageNumber, pageSize)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get flow draft", description = "Full draft including the canvas document and version.")
    public ApiResponse<FlowDesign> get(@PathVariable Long id) {
        return ApiResponse.success(service.getById(id).orElse(null));
    }

    @PostMapping("/{id}/save")
    @Operation(summary = "Save flow draft (auto-save)",
            description = "Stores the canvas document without semantic validation — drafts may be broken. "
                    + "The request must echo the loaded optimistic-lock version; a stale version is rejected "
                    + "so concurrent editor sessions cannot silently overwrite each other.")
    public ApiResponse<FlowDesign> save(@PathVariable Long id,
                                        @Valid @RequestBody FlowDesignSaveRequest request) {
        return ApiResponse.success(service.saveDraft(id, request));
    }

    @PostMapping("/{id}/delete")
    @Operation(summary = "Delete flow draft",
            description = "Deletes the draft by id. Returns false if the draft has been published.")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        return ApiResponse.success(service.deleteById(id));
    }

    @PostMapping("/{id}/duplicate")
    @Operation(summary = "Duplicate flow draft",
            description = "Copies the draft under a fresh flow code; publish state is not copied.")
    public ApiResponse<FlowDesign> duplicate(@PathVariable Long id,
                                             @RequestBody(required = false) FlowDesignDuplicateRequest request) {
        return ApiResponse.success(service.duplicateDesign(id, request));
    }

    @PostMapping("/validate")
    @Operation(summary = "Validate flow definition",
            description = "Runs the full compile pipeline on the posted document (which need not be saved) "
                    + "and returns every diagnostic as a success payload — each anchored to nodeId/edgeId/field "
                    + "for on-canvas markers. An empty diagnostics list means the document compiles.")
    public ApiResponse<FlowValidationResult> validate(@RequestBody DesignFlowDefinition definition) {
        return ApiResponse.success(FlowValidationResult.of(flowCompiler.validate(definition)));
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "Publish flow draft",
            description = "Reads the saved draft (single source of truth — no canvas payload accepted), "
                    + "compiles it, and publishes a new active revision. Reload the draft afterwards: "
                    + "publishing updates the draft's publish markers and therefore its version.")
    public ApiResponse<FlowBundleSummaryView> publish(@PathVariable Long id,
                                                      @RequestBody(required = false) FlowPublishRequest request) {
        String changeDescription = request == null ? null : request.changeDescription();
        return ApiResponse.success(flowPublishService.publishDraft(id, changeDescription));
    }

    @PostMapping("/{id}/restore")
    @Operation(summary = "Restore draft from bundle",
            description = "Overwrites the draft's canvas with the design snapshot stored in the specified "
                    + "published bundle and returns the updated draft.")
    public ApiResponse<FlowDesign> restore(@PathVariable Long id, @RequestParam Long bundleId) {
        return ApiResponse.success(service.restoreFromBundle(id, bundleId));
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "Get draft publish status",
            description = "Revision badge + dirty flag: dirty is true when the draft differs from the most "
                    + "recently published design.")
    public ApiResponse<FlowDesignStatusView> status(@PathVariable Long id) {
        return ApiResponse.success(service.getStatus(id));
    }

    @GetMapping("/{id}/availableVariables")
    @Operation(summary = "List variables available to a node",
            description = "Trigger payload keys, outputs declared by upstream nodes, and engine builtins — "
                    + "the data source for expression autocomplete and variableRef pickers. Omit nodeId "
                    + "for trigger + builtin variables only.")
    public ApiResponse<List<FlowVariableView>> availableVariables(@PathVariable Long id,
                                                                  @RequestParam(required = false) String nodeId) {
        DesignFlowDefinition definition = service.getById(id)
                .map(FlowDesign::getDesignJson)
                .orElse(null);
        return ApiResponse.success(variableCatalogService.availableVariables(definition, nodeId));
    }

    @PostMapping("/{id}/debugRun")
    @Operation(summary = "Debug-run the current draft",
            description = "Compiles the saved draft (compile errors return anchored diagnostics), registers "
                    + "a debug bundle (never active, hidden from revision lists, purged after retention) and "
                    + "executes it through the normal engine. NOT a sandbox — node side effects are real. "
                    + "Returns the compiled graph plus the execution state for an immediate canvas overlay.")
    public ApiResponse<FlowLaunchResponse> debugRun(@PathVariable Long id,
                                                    @RequestBody(required = false) FlowStartRequest request) {
        FlowStartRequest startRequest = request != null ? request : new FlowStartRequest();
        startRequest.setInitiatorId(currentUserId());
        return ApiResponse.success(flowLaunchService.debugRunDraft(id, startRequest));
    }

    @GetMapping("/{id}/debugRuns")
    @Operation(summary = "List debug-run history",
            description = "Past debug runs of this flow, newest first (trigger payload, node trace, "
                    + "final variables, error).")
    public ApiResponse<List<FlowDebugHistory>> debugRuns(@PathVariable Long id) {
        return service.getById(id)
                .map(design -> ApiResponse.success(
                        debugHistoryService.listByFlowCode(design.getFlowCode())))
                .orElseGet(() -> new ApiResponse<>(ResponseCode.REQUEST_NOT_FOUND.getCode(),
                        "FlowDesign not found: " + id, null));
    }

    private static String currentUserId() {
        Long userId = ContextHolder.getContext().getUserId();
        if (userId == null) {
            throw new FlowAuthorizationException("Authentication is required");
        }
        return userId.toString();
    }
}
