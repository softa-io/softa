package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.entity.FlowCcConfig;
import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;
import io.softa.starter.flow.service.FlowCcConfigService;

/**
 * Processes automatic CC rules based on flow/node-level CC configurations.
 * <p>
 * Evaluates conditions, resolves recipients from config, appends audit trails,
 * and dispatches notifications via {@link ApprovalNotificationService}.
 * </p>
 */
@Slf4j
@Component
public class AutoCcService {

    @Autowired(required = false)
    private FlowCcConfigService ccConfigService;

    @Autowired(required = false)
    private FlowAuditService auditService;

    @Autowired(required = false)
    private ApprovalNotificationService notificationService;

    /**
     * Process all matching CC configurations for the given timing.
     *
     * @param state     the current execution state
     * @param node      the current node (may be null for flow-level events)
     * @param ccTiming  the timing string (OnSubmit, OnApprove, OnReject, OnComplete)
     */
    public void processCc(FlowExecutionState state, CompiledFlowNode node, CcTiming ccTiming) {
        if (ccConfigService == null || state == null) {
            return;
        }

        String nodeId = node != null ? node.getNodeId() : null;
        List<FlowCcConfig> configs = ccConfigService.getActiveConfigs(state.getFlowCode(), nodeId, ccTiming);
        if (configs == null || configs.isEmpty()) {
            return;
        }

        for (FlowCcConfig config : configs) {
            try {
                processSingleCc(state, node, config);
            } catch (Exception e) {
                log.error("Error processing auto-CC config '{}' for instance {}: {}",
                        config.getCcName(), state.getInstanceId(), e.getMessage(), e);
            }
        }
    }

    private void processSingleCc(FlowExecutionState state, CompiledFlowNode node, FlowCcConfig config) {
        // Evaluate condition if present
        if (config.getCcCondition() != null && !config.getCcCondition().isBlank()) {
            try {
                Map<String, Object> env = new LinkedHashMap<>(state.getVariables() != null ? state.getVariables() : Map.of());
                boolean shouldCc = ComputeUtils.executeBoolean(config.getCcCondition(), env);
                if (!shouldCc) {
                    log.debug("CC condition not met for config '{}' on instance {}", config.getCcName(), state.getInstanceId());
                    return;
                }
            } catch (Exception e) {
                log.warn("Error evaluating CC condition for config '{}': {}", config.getCcName(), e.getMessage());
                return;
            }
        }

        // Resolve recipients from config
        List<String> recipientActorIds = resolveRecipients(config, state);
        if (recipientActorIds.isEmpty()) {
            log.debug("No CC recipients resolved for config '{}'", config.getCcName());
            return;
        }

        log.info("Processing auto-CC '{}' for instance {}, {} recipients, timing={}",
                config.getCcName(), state.getInstanceId(), recipientActorIds.size(), config.getCcTiming());

        // Add trace
        if (auditService != null && node != null) {
            auditService.addTrace(state, state.getFlowCode(), node, FlowTraceEventType.APPROVAL_CCED,
                    "Auto-CC '" + config.getCcName() + "' sent to " + recipientActorIds);
        }

        // Send notification
        if (Boolean.TRUE.equals(config.getSendNotification()) && notificationService != null) {
            String message = config.getMessageTemplate() != null
                    ? config.getMessageTemplate()
                    : "You have been CC'd on: " + state.getFlowCode();
            notificationService.notify(new FlowNotificationEvent.CcSent(state,
                    node != null ? node.getNodeId() : null,
                    recipientActorIds, message));
        }
    }

    /**
     * Resolve recipients from the CC config.
     * Supports recipientType: USER (comma-separated IDs in recipientConfig),
     * INITIATOR (uses state.initiatorId), or raw list.
     */
    private List<String> resolveRecipients(FlowCcConfig config, FlowExecutionState state) {
        List<String> recipients = new ArrayList<>();
        String type = config.getRecipientType();

        if ("INITIATOR".equalsIgnoreCase(type)) {
            if (state.getInitiatorId() != null) {
                recipients.add(state.getInitiatorId());
            }
        } else if ("USER".equalsIgnoreCase(type) && config.getRecipientConfig() != null) {
            // Expect comma-separated user IDs
            for (String id : config.getRecipientConfig().split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    recipients.add(trimmed);
                }
            }
        } else if (config.getRecipientConfig() != null) {
            // Fallback: treat as comma-separated IDs
            for (String id : config.getRecipientConfig().split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    recipients.add(trimmed);
                }
            }
        }

        return recipients;
    }
}

