package io.softa.starter.flow.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowParallelBranch;
import io.softa.starter.flow.runtime.engine.FlowStateChangeListener;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.support.FlowParallelBranchProjector;

/**
 * ORM-backed parallel branch execution record service.
 * Acts as a {@link FlowStateChangeListener} to synchronize branch records
 * from runtime state after each state change.
 */
@Service
public class FlowParallelBranchServiceImpl extends EntityServiceImpl<FlowParallelBranch, Long>
        implements FlowStateChangeListener {

    private final FlowParallelBranchProjector projector;

    public FlowParallelBranchServiceImpl(FlowParallelBranchProjector projector) {
        this.projector = projector;
    }

    private List<FlowParallelBranch> listByInstanceId(String instanceId) {
        Filters filters = new Filters().eq(FlowParallelBranch::getInstanceId, instanceId);
        return this.searchList(filters);
    }

    @Override
    public void onStateChanged(FlowExecutionState state) {
        syncFromState(state);
    }

    @Transactional(rollbackFor = Exception.class)
    public void syncFromState(FlowExecutionState state) {
        if (state == null || state.getInstanceId() == null) {
            return;
        }
        List<FlowParallelBranch> desiredBranches = projector.project(state);
        if (desiredBranches.isEmpty()) {
            return;
        }

        List<FlowParallelBranch> existing = listByInstanceId(state.getInstanceId());
        Map<String, FlowParallelBranch> existingByKey = new LinkedHashMap<>();
        for (FlowParallelBranch branch : existing) {
            existingByKey.put(keyOf(branch), branch);
        }

        // batch by collecting create / update lists separately.
        List<FlowParallelBranch> toCreate = new ArrayList<>();
        List<FlowParallelBranch> toUpdate = new ArrayList<>();

        for (FlowParallelBranch desired : desiredBranches) {
            String key = keyOf(desired);
            FlowParallelBranch found = existingByKey.get(key);
            if (found != null) {
                if (!Objects.equals(found.getStatus(), desired.getStatus())
                        || !Objects.equals(found.getEndTime(), desired.getEndTime())) {
                    found.setStatus(desired.getStatus());
                    found.setEndTime(desired.getEndTime());
                    found.setDurationMs(desired.getDurationMs());
                    found.setErrorMessage(desired.getErrorMessage());
                    toUpdate.add(found);
                }
            } else {
                toCreate.add(desired);
            }
        }
        if (!toCreate.isEmpty()) {
            this.createList(toCreate);
        }
        if (!toUpdate.isEmpty()) {
            this.updateList(toUpdate);
        }
    }

    private static String keyOf(FlowParallelBranch branch) {
        return branch.getForkNodeId() + "::" + branch.getBranchNodeId();
    }
}

