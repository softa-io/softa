package io.softa.starter.flow.service.impl;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.service.FlowBundleService;

@Service
public class FlowBundleServiceImpl extends EntityServiceImpl<FlowBundle, Long>
        implements FlowBundleService {

    /** What {@code FlowBundleMapper.toDefinition} reads — the design snapshot is left out. */
    private static final List<String> DEFINITION_FIELDS = List.of(
            LambdaUtils.getAttributeName(FlowBundle::getId),
            LambdaUtils.getAttributeName(FlowBundle::getTenantId),
            LambdaUtils.getAttributeName(FlowBundle::getFlowId),
            LambdaUtils.getAttributeName(FlowBundle::getCompiledJson));

    /** What a revision's cron job is made of. */
    private static final List<String> CRON_JOB_FIELDS = List.of(
            LambdaUtils.getAttributeName(FlowBundle::getId),
            LambdaUtils.getAttributeName(FlowBundle::getTenantId),
            LambdaUtils.getAttributeName(FlowBundle::getCronExpression));

    @Override
    public Optional<FlowBundle> findById(Long id) {
        return super.getById(id);
    }

    /**
     * Insert or update a bundle row. A create that collides on
     * {@code uk_tenant_flow_revision} propagates the duplicate-key exception —
     * the registry retries with a fresh revision instead of overwriting the row
     * that won the race.
     */
    @Override
    public void saveBundle(FlowBundle bundle) {
        if (bundle.getId() != null) {
            this.updateOne(bundle, false);
            return;
        }
        Long id = this.createOne(bundle);
        bundle.setId(id);
    }

    @Override
    public Optional<FlowBundle> findActiveByFlowId(String flowId) {
        Filters filters = new Filters()
                .eq(FlowBundle::getFlowId, flowId)
                .eq(FlowBundle::getActive, true);
        return this.searchOne(filters);
    }

    /**
     * Runtime lookup behind "start the active revision": the caller's own row permissions do
     * not apply, the tenant filter still does.
     */
    @Override
    @SkipPermissionCheck
    public Optional<Long> findActiveBundleId(String flowId) {
        Filters filters = new Filters()
                .eq(FlowBundle::getFlowId, flowId)
                .eq(FlowBundle::getActive, true);
        FlexQuery query = new FlexQuery(filters).select(FlowBundle::getId);
        return this.searchOne(query).map(FlowBundle::getId);
    }

    /** Revision-addressed lookup — internal backbone of {@code markAsActive}. */
    private Optional<FlowBundle> findByFlowIdAndRevision(String flowId, Integer revision) {
        Filters filters = new Filters()
                .eq(FlowBundle::getFlowId, flowId)
                .eq(FlowBundle::getRevision, revision);
        return this.searchOne(filters);
    }

    @Override
    public List<FlowBundle> listRevisionsByFlowId(String flowId) {
        Filters filters = new Filters().eq(FlowBundle::getFlowId, flowId);
        return this.searchList(filters).stream()
                // debug-run bundles are resolvable by id but are not revisions
                .filter(bundle -> !Boolean.TRUE.equals(bundle.getDebug()))
                .sorted(Comparator.comparingInt(FlowBundle::getRevision).reversed())
                .toList();
    }

    @Override
    public List<FlowBundle> getAllActiveFlow() {
        FlexQuery query = new FlexQuery(new Filters().eq(FlowBundle::getActive, true));
        query.select(DEFINITION_FIELDS);
        return this.searchList(query);
    }

    @Override
    public List<FlowBundle> listActiveCronBundles() {
        Filters filters = new Filters()
                .eq(FlowBundle::getActive, true)
                .isSet(FlowBundle::getCronExpression);
        FlexQuery query = new FlexQuery(filters).select(CRON_JOB_FIELDS);
        return this.searchList(query);
    }

    @Override
    public int getNextRevision(String flowId) {
        Filters filters = new Filters().eq(FlowBundle::getFlowId, flowId);
        return this.searchList(filters).stream()
                .mapToInt(FlowBundle::getRevision)
                .max()
                .orElse(0) + 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAsActive(String flowId, Integer revision) {
        Filters activeFilters = new Filters()
                .eq(FlowBundle::getFlowId, flowId)
                .eq(FlowBundle::getActive, true);
        for (FlowBundle bundle : this.searchList(activeFilters)) {
            if (!revision.equals(bundle.getRevision())) {
                writeActive(bundle.getId(), false);
            }
        }
        findByFlowIdAndRevision(flowId, revision).ifPresent(bundle -> {
            if (!Boolean.TRUE.equals(bundle.getActive())) {
                writeActive(bundle.getId(), true);
            }
        });
    }

    /**
     * Only the flag: written back whole, a bundle read with any field cut would clear that field — the
     * compiled definition among them.
     */
    private void writeActive(Long bundleId, boolean active) {
        FlowBundle patch = new FlowBundle();
        patch.setId(bundleId);
        patch.setActive(active);
        this.updateOne(patch);
    }

    /**
     * Transactional on the proxy-visible entry: the internal this-call to
     * {@code markAsActive} bypasses Spring's proxy, so its own annotation does
     * not apply on this path — and the deactivate+activate writes must be atomic
     * (a partial failure would leave the design with no active revision).
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<FlowBundle> activateBundle(Long bundleId) {
        Optional<FlowBundle> target = findById(bundleId);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        FlowBundle bundle = target.get();
        if (Boolean.TRUE.equals(bundle.getDebug())) {
            throw new FlowRuntimeException("Debug bundle " + bundleId + " cannot be activated");
        }
        markAsActive(bundle.getFlowId(), bundle.getRevision());
        return target;
    }
}
