package io.softa.starter.flow.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.base.security.EncryptUtils;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.dto.FlowDesignCreateRequest;
import io.softa.starter.flow.dto.FlowDesignDuplicateRequest;
import io.softa.starter.flow.dto.FlowDesignSaveRequest;
import io.softa.starter.flow.dto.FlowDesignStatusView;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.service.FlowBundleService;
import io.softa.starter.flow.service.FlowDesignService;
import tools.jackson.core.type.TypeReference;

@Service
public class FlowDesignServiceImpl extends EntityServiceImpl<FlowDesign, String>
        implements FlowDesignService {

    @Autowired(required = false)
    private FlowBundleService bundleService;

    @Override
    public FlowDesign createDesign(FlowDesignCreateRequest request) {
        FlowDesign design = new FlowDesign();
        design.setFlowName(request.flowName());
        design.setScenario(request.scenario());
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
        FlowDesign patch = patchOf(id);
        // optimistic lock: echo the version the editor loaded; a mismatch is rejected
        patch.setVersion(request.version());
        patch.setFlowName(request.flowName());
        patch.setScenario(request.scenario());
        patch.setDesignJson(alignedDefinition(request.designJson(), design));
        this.updateOne(patch);
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
        return new FlowDesignStatusView(design.getPublishedRevision(), activeBundleId, dirty);
    }

    /**
     * Refuse deletion of drafts that have already been published.
     */
    @Override
    public boolean deleteById(String id) {
        return getById(id)
                .filter(d -> d.getPublishedRevision() == null)
                .map(d -> super.deleteById(id))
                .orElse(false);
    }

    @Override
    public void upsertFromPublish(String flowId, Integer publishedRevision) {
        getById(flowId).ifPresent(design -> {
            FlowDesign patch = patchOf(flowId);
            patch.setPublishedRevision(publishedRevision);
            patch.setPublishedChecksum(checksumOf(design.getDesignJson()));
            updateOne(patch);
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
        FlowDesign patch = patchOf(flowId);
        patch.setDesignJson(alignedDefinition(bundle.getDesignJson(), design));
        updateOne(patch);
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
     * Keep the document's identity fields aligned with the entity's denormalised
     * columns: flowName and scenario follow the entity. Publish compiles the aligned
     * document too, so a document written past this service cannot change the scenario.
     */
    public static DesignFlowDefinition alignedDefinition(DesignFlowDefinition definition, FlowDesign design) {
        DesignFlowDefinition doc = definition != null ? definition : emptyDefinition();
        doc.setName(design.getFlowName());
        doc.setScenario(design.getScenario());
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
