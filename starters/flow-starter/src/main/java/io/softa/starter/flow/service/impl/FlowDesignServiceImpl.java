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
public class FlowDesignServiceImpl extends EntityServiceImpl<FlowDesign, Long>
        implements FlowDesignService {

    @Autowired(required = false)
    private FlowBundleService bundleService;

    @Override
    public FlowDesign createDesign(FlowDesignCreateRequest request) {
        FlowDesign design = new FlowDesign();
        design.setFlowName(request.flowName());
        design.setFlowCode(request.flowCode());
        design.setScenario(request.scenario());
        design.setDesignJson(alignedDefinition(request.designJson(), design));
        Long id = this.createOne(design);
        return getById(id).orElse(design);
    }

    @Override
    public FlowDesign saveDraft(Long id, FlowDesignSaveRequest request) {
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
    public FlowDesign duplicateDesign(Long id, FlowDesignDuplicateRequest request) {
        FlowDesign source = requireDesign(id);
        String newCode = request != null && request.newFlowCode() != null
                ? request.newFlowCode()
                : deriveCopyCode(source.getFlowCode());
        String newName = request != null && request.newFlowName() != null
                ? request.newFlowName()
                : source.getFlowName() + " (copy)";

        FlowDesign copy = new FlowDesign();
        copy.setFlowName(newName);
        copy.setFlowCode(newCode);
        copy.setScenario(source.getScenario());
        copy.setDesignJson(alignedDefinition(deepCopy(source.getDesignJson()), copy));
        Long copyId = this.createOne(copy);
        return getById(copyId).orElse(copy);
    }

    @Override
    public FlowDesignStatusView getStatus(Long id) {
        FlowDesign design = requireDesign(id);
        Long activeBundleId = bundleService == null ? null
                : bundleService.findActiveByDesignId(id).map(FlowBundle::getId).orElse(null);
        boolean dirty = design.getPublishedChecksum() == null
                || !design.getPublishedChecksum().equals(checksumOf(design.getDesignJson()));
        return new FlowDesignStatusView(design.getPublishedRevision(), activeBundleId, dirty);
    }

    /**
     * Refuse deletion of drafts that have already been published.
     */
    @Override
    public boolean deleteById(Long id) {
        return getById(id)
                .filter(d -> d.getPublishedRevision() == null)
                .map(d -> super.deleteById(id))
                .orElse(false);
    }

    @Override
    public void upsertFromPublish(Long designId, Integer publishedRevision) {
        getById(designId).ifPresent(design -> {
            design.setPublishedRevision(publishedRevision);
            design.setPublishedChecksum(checksumOf(design.getDesignJson()));
            updateOne(design, false);
        });
    }

    @Override
    public FlowDesign restoreFromBundle(Long designId, Long bundleId) {
        if (bundleService == null) {
            throw new FlowRuntimeException("FlowBundleService is not available; cannot restore from bundle");
        }
        FlowBundle bundle = bundleService.findById(bundleId)
                .orElseThrow(() -> new FlowRuntimeException("Flow bundle not found: " + bundleId));
        if (bundle.getDesignJson() == null) {
            throw new FlowRuntimeException("Bundle " + bundleId + " carries no design snapshot to restore");
        }
        // Ownership check: the bundle must belong to this design.
        if (bundle.getDesignId() == null || !bundle.getDesignId().equals(designId)) {
            throw new FlowRuntimeException(
                    "Bundle " + bundleId + " does not belong to design " + designId);
        }
        FlowDesign design = requireDesign(designId);
        design.setDesignJson(bundle.getDesignJson());
        updateOne(design, false);
        return requireDesign(designId);
    }

    /** SHA-256 over the canonical JSON form; the single checksum used by publish and status. */
    public static String checksumOf(DesignFlowDefinition definition) {
        if (definition == null) {
            return null;
        }
        return EncryptUtils.computeSha256(JsonUtils.objectToString(definition));
    }

    private FlowDesign requireDesign(Long id) {
        return getById(id).orElseThrow(() -> new FlowRuntimeException("FlowDesign not found: " + id));
    }

    /**
     * Keep the document's identity fields aligned with the entity's denormalised
     * columns: flowCode is immutable and flowName/scenario follow the entity.
     */
    private static DesignFlowDefinition alignedDefinition(DesignFlowDefinition definition, FlowDesign design) {
        DesignFlowDefinition doc = definition != null ? definition : emptyDefinition();
        doc.setCode(design.getFlowCode());
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

    private String deriveCopyCode(String sourceCode) {
        String base = sourceCode + "-copy";
        String candidate = base;
        int suffix = 2;
        while (this.count(new Filters().eq(FlowDesign::getFlowCode, candidate)) > 0) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}
