package io.softa.starter.flow.service.impl;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.service.FlowBundleService;

@Service
public class FlowBundleServiceImpl extends EntityServiceImpl<FlowBundle, Long>
        implements FlowBundleService {

    @Override
    public Optional<FlowBundle> findById(Long id) {
        return super.getById(id);
    }

    /**
     * Insert or update a bundle row. A create that collides on
     * {@code uk_tenant_design_revision} propagates the duplicate-key exception —
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
    public Optional<FlowBundle> findActiveByDesignId(Long designId) {
        Filters filters = new Filters()
                .eq(FlowBundle::getDesignId, designId)
                .eq(FlowBundle::getActive, true);
        return this.searchOne(filters);
    }

    @Override
    public Optional<FlowBundle> findByDesignIdAndRevision(Long designId, Integer revision) {
        Filters filters = new Filters()
                .eq(FlowBundle::getDesignId, designId)
                .eq(FlowBundle::getRevision, revision);
        return this.searchOne(filters);
    }

    @Override
    public List<FlowBundle> listRevisionsByDesignId(Long designId) {
        Filters filters = new Filters().eq(FlowBundle::getDesignId, designId);
        return this.searchList(filters).stream()
                // debug-run bundles are resolvable by id but are not revisions
                .filter(bundle -> !Boolean.TRUE.equals(bundle.getDebug()))
                .sorted(Comparator.comparingInt(FlowBundle::getRevision).reversed())
                .toList();
    }

    @Override
    public List<FlowBundle> getAllActiveFlow() {
        Filters filters = new Filters().eq(FlowBundle::getActive, true);
        return this.searchList(filters);
    }

    @Override
    public int getNextRevision(Long designId) {
        Filters filters = new Filters().eq(FlowBundle::getDesignId, designId);
        return this.searchList(filters).stream()
                .mapToInt(FlowBundle::getRevision)
                .max()
                .orElse(0) + 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAsActive(Long designId, Integer revision) {
        Filters activeFilters = new Filters()
                .eq(FlowBundle::getDesignId, designId)
                .eq(FlowBundle::getActive, true);
        for (FlowBundle bundle : this.searchList(activeFilters)) {
            if (!revision.equals(bundle.getRevision())) {
                bundle.setActive(false);
                this.updateOne(bundle, false);
            }
        }
        findByDesignIdAndRevision(designId, revision).ifPresent(bundle -> {
            if (!Boolean.TRUE.equals(bundle.getActive())) {
                bundle.setActive(true);
                this.updateOne(bundle, false);
            }
        });
    }

    @Override
    public Optional<FlowBundle> activateBundle(Long bundleId) {
        Optional<FlowBundle> target = findById(bundleId);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        FlowBundle bundle = target.get();
        if (Boolean.TRUE.equals(bundle.getDebug())) {
            throw new FlowRuntimeException("Debug bundle " + bundleId + " cannot be activated");
        }
        markAsActive(bundle.getDesignId(), bundle.getRevision());
        return target;
    }
}
