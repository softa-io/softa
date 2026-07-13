package io.softa.starter.flow.service.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowCcConfig;
import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.service.FlowCcConfigService;

/**
 * ORM-backed CC configuration service.
 */
@Service
public class FlowCcConfigServiceImpl extends EntityServiceImpl<FlowCcConfig, Long>
        implements FlowCcConfigService {

    @Override
    public List<FlowCcConfig> getActiveConfigs(String flowCode, String nodeId, CcTiming ccTiming) {
        Filters filters = new Filters()
                .eq(FlowCcConfig::getFlowCode, flowCode)
                .eq(FlowCcConfig::getActive, true);
        if (ccTiming != null) {
            filters.eq(FlowCcConfig::getCcTiming, ccTiming);
        }
        return this.searchList(filters).stream()
                .filter(c -> matchesNode(c, nodeId))
                .toList();
    }

    @Override
    public FlowCcConfig createConfig(FlowCcConfig config) {
        Long id = this.createOne(config);
        config.setId(id);
        return config;
    }

    @Override
    public List<FlowCcConfig> listByFlowCode(String flowCode) {
        Filters filters = new Filters().eq(FlowCcConfig::getFlowCode, flowCode);
        return this.searchList(filters);
    }

    @Override
    public void deactivateConfig(Long configId) {
        this.getById(configId).ifPresent(c -> {
            c.setActive(false);
            this.updateOne(c, false);
        });
    }

    private boolean matchesNode(FlowCcConfig config, String nodeId) {
        // Flow-level configs (nodeId == null) always match
        if (config.getNodeId() == null || config.getNodeId().isBlank()) {
            return true;
        }
        // Node-specific configs only match the specific node
        return Objects.equals(config.getNodeId(), nodeId);
    }
}

