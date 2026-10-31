package io.softa.starter.flow.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.base.security.EncryptUtils;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
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
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.support.ComputeOutputs;
import io.softa.starter.flow.service.support.SubmitConditions;
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

    @Autowired(required = false)
    private FlowInstanceService instanceService;

    private static final String MODEL_NAME = LambdaUtils.getAttributeName(FlowDesign::getModelName);

    @Override
    public FlowDesign createDesign(FlowDesignCreateRequest request) {
        FlowDesign design = new FlowDesign();
        design.setFlowName(request.flowName());
        design.setScenario(request.scenario());
        design.setEnabled(true);
        if (FlowScenario.APPROVAL.equals(request.scenario())) {
            String document = StringUtils.hasText(request.modelName()) ? request.modelName()
                    : modelNameOf(request.designJson());
            requireApprovableDocument(document);
            design.setModelName(document);
        }
        design.setDesignJson(alignedDefinition(request.designJson(), design));
        String id = this.createOne(design);
        return getById(id).orElse(design);
    }

    @Override
    public FlowDesign saveDraft(String id, FlowDesignSaveRequest request) {
        FlowDesign design = requireDesign(id);
        if (request.flowName() != null) {
            design.setFlowName(request.flowName());
        }
        if (request.scenario() != null) {
            design.setScenario(request.scenario());
        }
        String storedModel = design.getModelName();
        FlowDesign patch = patchOf(id);
        // optimistic lock: echo the version the editor loaded; a mismatch is rejected
        patch.setVersion(request.version());
        patch.setFlowName(request.flowName());
        patch.setScenario(request.scenario());
        patch.setDesignJson(alignedDefinition(request.designJson(), design));
        writeWithModel(patch, design.getModelName(), storedModel);
        return requireDesign(id);
    }

    @Override
    public FlowDesign duplicateDesign(String id, FlowDesignDuplicateRequest request) {
        FlowDesign source = requireDesign(id);
        String newName = request != null && request.newFlowName() != null
                ? request.newFlowName()
                : deriveCopyName(source.getFlowName());

        FlowDesign copy = new FlowDesign();
        copy.setFlowName(newName);
        copy.setScenario(source.getScenario());
        // an approval flow's copy approves the same document — the trigger's condition is what to change
        copy.setModelName(FlowScenario.APPROVAL.equals(source.getScenario()) ? source.getModelName() : null);
        copy.setEnabled(true);
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
            long unfinished = instanceService == null ? 0 : instanceService.countUnfinished(id);
            if (unfinished > 0) {
                throw new FlowActionValidationException("Flow '" + design.getFlowName() + "' cannot be disabled: "
                        + unfinished + " of its instances have not finished — records still in approval among them. "
                        + "Let them finish, or end them in Flow Instances, first.");
            }
            List<String> callers = callersOf(id);
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
            if (bundleService != null && bundleRegistry != null) {
                bundleService.findActiveByFlowId(id).map(FlowBundleMapper::toDefinition).ifPresent(active -> {
                    List<CompiledFlowDefinition> enabled = bundleRegistry.listTriggerCandidates(active.getModelName());
                    ComputeOutputs.requireOwn(id, active, enabled);
                    SubmitConditions.requireDistinct(id, active, enabled);
                });
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
        Map<String, List<String>> flowNames = new HashMap<>();
        Set<String> modelNames = approvalHandlers.modelNames();
        if (!modelNames.isEmpty()) {
            this.searchList(new Filters()
                            .eq(FlowDesign::getScenario, FlowScenario.APPROVAL.getType())
                            .in(FlowDesign::getModelName, List.copyOf(modelNames)))
                    .forEach(design -> flowNames.computeIfAbsent(design.getModelName(), model -> new ArrayList<>())
                            .add(design.getFlowName()));
        }
        return modelNames.stream()
                .map(modelName -> new ApprovalModelView(modelName, labelOf(modelName),
                        flowNames.getOrDefault(modelName, List.of()).stream()
                                .sorted(String.CASE_INSENSITIVE_ORDER)
                                .toList()))
                .sorted(Comparator.comparing(ApprovalModelView::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * A new approval flow needs a document the application approves. One that has approval flows already takes
     * another — for a country, a company, a kind of record — told apart from them by its submit condition.
     */
    private void requireApprovableDocument(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            throw new FlowActionValidationException("An approval flow approves one document: choose it.");
        }
        if (approvalHandlers != null && approvalHandlers.find(modelName).isEmpty()) {
            throw new FlowActionValidationException("Records of '" + modelName
                    + "' cannot be approved: the application has no approval handler for them.");
        }
    }

    private static String labelOf(String modelName) {
        return ModelManager.existModel(modelName) && StringUtils.hasText(ModelManager.getModel(modelName).getLabel())
                ? ModelManager.getModel(modelName).getLabel()
                : modelName;
    }

    private void switchTo(FlowDesign design, boolean enabled) {
        FlowDesign patch = patchOf(design.getId());
        patch.setEnabled(enabled);
        updateOne(patch);
        design.setEnabled(enabled);
        if (bundleRegistry != null) {
            // what fires on this instance follows at once; others within the trigger cache's TTL
            bundleRegistry.refreshActiveForFlowId(design.getId());
        }
    }

    private static boolean isEnabled(FlowDesign design) {
        return !Boolean.FALSE.equals(design.getEnabled());
    }

    /**
     * The names of the flows that may still run {@code flowId} as a subflow: the enabled ones whose effective revision
     * calls it, and those whose unfinished instances run a revision that does — an instance runs on the revision it
     * started on, however the flow changed since.
     */
    private List<String> callersOf(String flowId) {
        if (bundleRegistry == null) {
            return List.of();
        }
        Stream<CompiledFlowDefinition> running = instanceService == null ? Stream.empty()
                : instanceService.unfinishedBundleIds().stream()
                        .map(bundleRegistry::getByBundleId)
                        .flatMap(Optional::stream);
        return Stream.concat(bundleRegistry.list().stream(), running)
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
            FlowDesign patch = patchOf(flowId);
            patch.setPublishedRevision(publishedRevision);
            patch.setPublishedChecksum(checksumOf(design.getDesignJson()));
            if (design.getDesignJson() == null) {
                updateOne(patch);
                return;
            }
            // A design written past this service — a seeded one — takes its model column from its trigger here.
            writeWithModel(patch, modelNameFor(design, design.getDesignJson()), design.getModelName());
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
        String storedModel = design.getModelName();
        FlowDesign patch = patchOf(flowId);
        patch.setDesignJson(alignedDefinition(bundle.getDesignJson(), design));
        writeWithModel(patch, design.getModelName(), storedModel);
        return requireDesign(flowId);
    }

    /** SHA-256 over the canonical JSON form; the single checksum used by publish and status. */
    public static String checksumOf(DesignFlowDefinition definition) {
        if (definition == null) {
            return null;
        }
        return EncryptUtils.computeSha256(JsonUtils.objectToString(definition));
    }

    /**
     * Writes {@code patch} — the id and the fields it sets — with the model column at {@code modelName}, what the
     * design's trigger names now. A patch skips nulls, so a model the trigger no longer names (it starts on no record
     * now) is cleared by naming the column in the row written.
     */
    private void writeWithModel(FlowDesign patch, String modelName, String storedModelName) {
        patch.setModelName(modelName);
        if (modelName != null || storedModelName == null) {
            updateOne(patch);
            return;
        }
        Map<String, Object> row = BeanTool.objectToMap(patch, true);
        row.put(MODEL_NAME, null);
        modelService.updateOne(this.modelName, row);
    }

    /**
     * An update carrying only the fields a method sets. Written back whole, with its nulls, a design read with
     * any field cut would clear that field: the published revision, the checksum, the document itself.
     */
    private static FlowDesign patchOf(String id) {
        FlowDesign patch = new FlowDesign();
        patch.setId(id);
        return patch;
    }

    private FlowDesign requireDesign(String id) {
        return getById(id).orElseThrow(() -> new FlowRuntimeException("FlowDesign not found: " + id));
    }

    /**
     * Keep the document and the entity's denormalised columns in step. The flow's name and scenario follow the
     * entity. The model goes with the trigger: any flow but an Approval one names it in its trigger, and the entity's
     * {@code modelName} column follows that, empty for a trigger that starts on no record. An Approval flow's
     * trigger is a submission of the flow's document, fixed since the flow was created: the document is put back
     * from the entity's {@code modelName}, and the condition the draft gives the trigger is kept. A design that names
     * its document only in its trigger (a seeded one) has the column taken from it the first time. Publish compiles
     * the aligned document too, so a document written past this service cannot change the scenario or an Approval
     * flow's document.
     */
    public static DesignFlowDefinition alignedDefinition(DesignFlowDefinition definition, FlowDesign design) {
        DesignFlowDefinition doc = definition != null ? definition : emptyDefinition();
        doc.setName(design.getFlowName());
        doc.setScenario(design.getScenario());
        design.setModelName(modelNameFor(design, doc));
        if (FlowScenario.APPROVAL.equals(design.getScenario())) {
            doc.setTrigger(doc.getTrigger() instanceof SubmitTrigger submit
                    ? submit.onDocument(design.getModelName())
                    : new SubmitTrigger(design.getModelName()));
        }
        return doc;
    }

    /** The model column a design takes: an Approval flow's document once it has one, else what its trigger names. */
    private static String modelNameFor(FlowDesign design, DesignFlowDefinition doc) {
        if (FlowScenario.APPROVAL.equals(design.getScenario()) && StringUtils.hasText(design.getModelName())) {
            return design.getModelName();
        }
        return modelNameOf(doc);
    }

    /** The model the document's trigger names, or null. */
    private static String modelNameOf(DesignFlowDefinition definition) {
        String modelName = definition == null || definition.getTrigger() == null ? null
                : definition.getTrigger().modelName();
        return StringUtils.hasText(modelName) ? modelName : null;
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
