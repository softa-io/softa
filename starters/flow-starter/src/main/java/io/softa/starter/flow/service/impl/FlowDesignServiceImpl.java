package io.softa.starter.flow.service.impl;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.base.security.EncryptUtils;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.dto.ApprovalModelView;
import io.softa.starter.flow.dto.FlowDesignCreateRequest;
import io.softa.starter.flow.dto.FlowDesignDuplicateRequest;
import io.softa.starter.flow.dto.FlowDesignSaveRequest;
import io.softa.starter.flow.dto.FlowDesignStatusView;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleMapper;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.ModelApprovalHandlerRegistry;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.nodeconfig.SubflowNodeConfig;
import io.softa.starter.flow.service.FlowBundleService;
import io.softa.starter.flow.service.FlowDesignService;
import io.softa.starter.flow.service.support.ComputeOutputs;
import tools.jackson.core.type.TypeReference;

@Service
public class FlowDesignServiceImpl extends EntityServiceImpl<FlowDesign, String>
        implements FlowDesignService {

    @Autowired(required = false)
    private FlowBundleService bundleService;

    @Autowired(required = false)
    private FlowBundleRegistry bundleRegistry;

    @Autowired(required = false)
    private ModelApprovalHandlerRegistry approvalHandlers;

    @Override
    public FlowDesign createDesign(FlowDesignCreateRequest request) {
        if (FlowScenario.APPROVAL.equals(request.scenario())) {
            requireDocumentWithoutFlow(request.modelName());
        }
        FlowDesign design = new FlowDesign();
        design.setFlowName(request.flowName());
        design.setScenario(request.scenario());
        design.setModelName(StringUtils.hasText(request.modelName()) ? request.modelName() : null);
        design.setDesignJson(alignedDefinition(request.designJson(), design));
        String id = this.createOne(design);
        return getById(id).orElse(design);
    }

    @Override
    public FlowDesign saveDraft(String id, FlowDesignSaveRequest request) {
        FlowDesign design = requireDesign(id);
        // optimistic lock: echo the version the editor loaded; a mismatch is rejected
        design.setVersion(request.version());
        if (request.flowName() != null) {
            design.setFlowName(request.flowName());
        }
        if (request.scenario() != null) {
            design.setScenario(request.scenario());
        }
        design.setDesignJson(alignedDefinition(request.designJson(), design));
        this.updateOne(design, false);
        return requireDesign(id);
    }

    @Override
    public FlowDesign duplicateDesign(String id, FlowDesignDuplicateRequest request) {
        FlowDesign source = requireDesign(id);
        if (FlowScenario.APPROVAL.equals(source.getScenario())) {
            throw new FlowActionValidationException("An approval flow cannot be copied: a document has one approval "
                    + "flow. Edit '" + source.getFlowName() + "' instead.");
        }
        String newName = request != null && request.newFlowName() != null
                ? request.newFlowName()
                : deriveCopyName(source.getFlowName());

        FlowDesign copy = new FlowDesign();
        copy.setFlowName(newName);
        copy.setScenario(source.getScenario());
        copy.setModelName(source.getModelName());
        copy.setDesignJson(alignedDefinition(deepCopy(source.getDesignJson()), copy));
        String copyId = this.createOne(copy);
        return getById(copyId).orElse(copy);
    }

    @Override
    public FlowDesignStatusView getStatus(String id) {
        FlowDesign design = requireDesign(id);
        Long activeBundleId = bundleService == null ? null
                : bundleService.findActiveByFlowId(id).map(FlowBundle::getId).orElse(null);
        boolean dirty = design.getPublishedChecksum() == null
                || !design.getPublishedChecksum().equals(checksumOf(design.getDesignJson()));
        return new FlowDesignStatusView(design.getPublishedRevision(), activeBundleId, dirty, isEnabled(design));
    }

    @Override
    public FlowDesignStatusView disable(String id) {
        FlowDesign design = requireDesign(id);
        if (isEnabled(design)) {
            List<String> callers = enabledCallersOf(id);
            if (!callers.isEmpty()) {
                throw new FlowActionValidationException("Flow '" + design.getFlowName() + "' cannot be disabled: "
                        + String.join(", ", callers) + " call it as a subflow. Disable or change them first.");
            }
            switchTo(design, false);
        }
        return getStatus(id);
    }

    @Override
    public FlowDesignStatusView enable(String id) {
        FlowDesign design = requireDesign(id);
        if (!isEnabled(design)) {
            if (FlowScenario.APPROVAL.equals(design.getScenario())) {
                otherApprovalFlows(design.getModelName(), id, true).stream().findFirst().ifPresent(other -> {
                    throw new FlowActionValidationException("Flow '" + design.getFlowName() + "' cannot be enabled: '"
                            + other.getFlowName() + "' already approves this document. Disable it first.");
                });
            }
            if (FlowScenario.COMPUTE.equals(design.getScenario()) && bundleService != null && bundleRegistry != null) {
                bundleService.findActiveByFlowId(id).map(FlowBundleMapper::toDefinition).ifPresent(active ->
                        ComputeOutputs.requireOwn(id, active, bundleRegistry.listTriggerCandidates(active.getModelName())));
            }
            switchTo(design, true);
        }
        return getStatus(id);
    }

    @Override
    public List<ApprovalModelView> listApprovalModels() {
        if (approvalHandlers == null) {
            return List.of();
        }
        Map<String, FlowDesign> flows = new HashMap<>();
        Set<String> modelNames = approvalHandlers.modelNames();
        if (!modelNames.isEmpty()) {
            this.searchList(new Filters()
                            .eq(FlowDesign::getScenario, FlowScenario.APPROVAL.getType())
                            .in(FlowDesign::getModelName, List.copyOf(modelNames)))
                    .forEach(design -> flows.putIfAbsent(design.getModelName(), design));
        }
        return modelNames.stream()
                .map(modelName -> {
                    FlowDesign flow = flows.get(modelName);
                    return new ApprovalModelView(modelName, labelOf(modelName),
                            flow == null ? null : flow.getId(),
                            flow == null ? null : flow.getFlowName(),
                            flow == null ? null : isEnabled(flow));
                })
                .sorted(Comparator.comparing(ApprovalModelView::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** A new approval flow needs a document the application approves that has no approval flow yet. */
    private void requireDocumentWithoutFlow(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            throw new FlowActionValidationException("An approval flow approves one document: choose it.");
        }
        if (approvalHandlers != null && approvalHandlers.find(modelName).isEmpty()) {
            throw new FlowActionValidationException("Records of '" + modelName
                    + "' cannot be approved: the application has no approval handler for them.");
        }
        otherApprovalFlows(modelName, null, false).stream().findFirst().ifPresent(existing -> {
            throw new FlowActionValidationException("'" + labelOf(modelName) + "' already has an approval flow, '"
                    + existing.getFlowName() + "': a document has one. Edit it instead.");
        });
    }

    /** The approval flows of {@code modelName} other than {@code exceptId}; the enabled ones only when asked. */
    private List<FlowDesign> otherApprovalFlows(String modelName, String exceptId, boolean enabledOnly) {
        Filters filters = new Filters()
                .eq(FlowDesign::getScenario, FlowScenario.APPROVAL.getType())
                .eq(FlowDesign::getModelName, modelName);
        if (exceptId != null) {
            filters.ne(FlowDesign::getId, exceptId);
        }
        if (enabledOnly) {
            filters.eq(FlowDesign::getEnabled, true);
        }
        return this.searchList(filters);
    }

    private static String labelOf(String modelName) {
        return ModelManager.existModel(modelName) && StringUtils.hasText(ModelManager.getModel(modelName).getLabel())
                ? ModelManager.getModel(modelName).getLabel()
                : modelName;
    }

    private void switchTo(FlowDesign design, boolean enabled) {
        design.setEnabled(enabled);
        updateOne(design, false);
        if (bundleRegistry != null) {
            // what fires on this instance follows at once; others within the trigger cache's TTL
            bundleRegistry.refreshActiveForFlowId(design.getId());
        }
    }

    private static boolean isEnabled(FlowDesign design) {
        return !Boolean.FALSE.equals(design.getEnabled());
    }

    /** The names of the enabled flows whose effective revision runs {@code flowId} as a subflow. */
    private List<String> enabledCallersOf(String flowId) {
        if (bundleRegistry == null) {
            return List.of();
        }
        return bundleRegistry.list().stream()
                .filter(definition -> !flowId.equals(definition.getFlowId()) && callsSubflow(definition, flowId))
                .map(CompiledFlowDefinition::getFlowName)
                .distinct()
                .toList();
    }

    private static boolean callsSubflow(CompiledFlowDefinition definition, String flowId) {
        return definition.getNodeIndex() != null && definition.getNodeIndex().values().stream()
                .anyMatch(node -> node.getParsedConfig() instanceof SubflowNodeConfig config
                        && Objects.equals(flowId, config.getSubflowId()));
    }

    @Override
    public void upsertFromPublish(String flowId, Integer publishedRevision) {
        getById(flowId).ifPresent(design -> {
            design.setPublishedRevision(publishedRevision);
            design.setPublishedChecksum(checksumOf(design.getDesignJson()));
            updateOne(design, false);
        });
    }

    @Override
    public FlowDesign restoreFromBundle(String flowId, Long bundleId) {
        if (bundleService == null) {
            throw new FlowRuntimeException("FlowBundleService is not available; cannot restore from bundle");
        }
        FlowBundle bundle = bundleService.findById(bundleId)
                .orElseThrow(() -> new FlowRuntimeException("Flow bundle not found: " + bundleId));
        if (bundle.getDesignJson() == null) {
            throw new FlowRuntimeException("Bundle " + bundleId + " carries no design snapshot to restore");
        }
        // Ownership check: the bundle must belong to this design.
        if (bundle.getFlowId() == null || !bundle.getFlowId().equals(flowId)) {
            throw new FlowRuntimeException(
                    "Bundle " + bundleId + " does not belong to design " + flowId);
        }
        FlowDesign design = requireDesign(flowId);
        design.setDesignJson(alignedDefinition(bundle.getDesignJson(), design));
        updateOne(design, false);
        return requireDesign(flowId);
    }

    /** SHA-256 over the canonical JSON form; the single checksum used by publish and status. */
    public static String checksumOf(DesignFlowDefinition definition) {
        if (definition == null) {
            return null;
        }
        return EncryptUtils.computeSha256(JsonUtils.objectToString(definition));
    }

    private FlowDesign requireDesign(String id) {
        return getById(id).orElseThrow(() -> new FlowRuntimeException("FlowDesign not found: " + id));
    }

    /**
     * Keep the document's identity fields aligned with the entity's denormalised
     * columns: flowName, scenario and modelName follow the entity. Publish compiles the aligned
     * document too, so a document written past this service cannot change the scenario or the model.
     */
    public static DesignFlowDefinition alignedDefinition(DesignFlowDefinition definition, FlowDesign design) {
        DesignFlowDefinition doc = definition != null ? definition : emptyDefinition();
        doc.setName(design.getFlowName());
        doc.setScenario(design.getScenario());
        doc.setModelName(design.getModelName());
        return doc;
    }

    private static DesignFlowDefinition emptyDefinition() {
        return DesignFlowDefinition.builder()
                .graph(FlowGraphDocument.builder().nodes(List.of()).edges(List.of()).build())
                .build();
    }

    private static DesignFlowDefinition deepCopy(DesignFlowDefinition definition) {
        if (definition == null) {
            return null;
        }
        return JsonUtils.stringToObject(JsonUtils.objectToString(definition), new TypeReference<>() {});
    }

    /** "Name (copy)", then "Name (copy 2)", … — the first one no flow of this tenant holds yet. */
    private String deriveCopyName(String sourceName) {
        String candidate = sourceName + " (copy)";
        int suffix = 2;
        while (this.count(new Filters().eq(FlowDesign::getFlowName, candidate)) > 0) {
            candidate = sourceName + " (copy " + suffix++ + ")";
        }
        return candidate;
    }
}
