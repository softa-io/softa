package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Reads and evaluates form field permissions from {@link ApprovalNodeConfig}. The node-based
 * helpers are pure functions of the compiled node (static); the instance-scoped lookup resolves
 * the node from a running instance via the engine + bundle registry.
 */
@Component
public class FormPermissionService {

    private final FlowRuntimeEngine runtimeEngine;
    private final FlowBundleRegistry bundleRegistry;

    public FormPermissionService(FlowRuntimeEngine runtimeEngine,
                                 FlowBundleRegistry bundleRegistry) {
        this.runtimeEngine = runtimeEngine;
        this.bundleRegistry = bundleRegistry;
    }

    /**
     * Resolve field permissions for a node within a running instance.
     * Returns an empty map if the instance, bundle, or node cannot be found.
     */
    public Map<String, FormFieldPermission> getFieldPermissions(String instanceId, String nodeId) {
        FlowExecutionState state = runtimeEngine.getInstance(instanceId).orElse(null);
        if (state == null) {
            return Collections.emptyMap();
        }
        CompiledFlowDefinition definition = state.getBundleId() != null
                ? bundleRegistry.getByBundleId(state.getBundleId()).orElse(null)
                : null;
        if (definition == null) {
            return Collections.emptyMap();
        }
        CompiledFlowNode node = definition.getNodeIndex().get(nodeId);
        if (node == null) {
            return Collections.emptyMap();
        }
        return getFieldPermissions(node);
    }

    // ── Pure node-based helpers (no instance state) ──────────────────────────

    /** Get the full field permission map for a compiled node. */
    public static Map<String, FormFieldPermission> getFieldPermissions(CompiledFlowNode node) {
        if (node == null || !(node.getParsedConfig() instanceof ApprovalNodeConfig cfg)
                || cfg.getFormPermissions() == null) {
            return Collections.emptyMap();
        }
        return new HashMap<>(cfg.getFormPermissions());
    }

    /** Check if a field is editable (editable or required). */
    public static boolean isFieldEditable(CompiledFlowNode node, String fieldName) {
        FormFieldPermission perm = getFieldPermissions(node).get(fieldName);
        return perm == FormFieldPermission.EDITABLE || perm == FormFieldPermission.REQUIRED;
    }

    /** Check if a field is required. */
    public static boolean isFieldRequired(CompiledFlowNode node, String fieldName) {
        return getFieldPermissions(node).get(fieldName) == FormFieldPermission.REQUIRED;
    }

    /** Check if a field is hidden. */
    public static boolean isFieldHidden(CompiledFlowNode node, String fieldName) {
        return getFieldPermissions(node).get(fieldName) == FormFieldPermission.HIDDEN;
    }

    /**
     * Validate form data against node permissions.
     *
     * @return list of validation error messages (empty if valid)
     */
    public static List<String> validateFormData(CompiledFlowNode node, Map<String, Object> formData) {
        Map<String, FormFieldPermission> permissions = getFieldPermissions(node);
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, FormFieldPermission> entry : permissions.entrySet()) {
            if (entry.getValue() == FormFieldPermission.REQUIRED) {
                String fieldName = entry.getKey();
                Object value = formData == null ? null : formData.get(fieldName);
                if (value == null || (value instanceof String s && s.isBlank())) {
                    errors.add("Field '" + fieldName + "' is required");
                }
            }
        }
        return errors;
    }

    /**
     * Filter form data based on permissions: drops both HIDDEN and READONLY edits. The write is a
     * <em>partial</em> update of the remaining (editable) fields, so dropped columns are simply left
     * untouched on the stored row — no original-row read is needed.
     */
    public static Map<String, Object> filterFormData(CompiledFlowNode node, Map<String, Object> formData) {
        if (formData == null) {
            return Collections.emptyMap();
        }
        Map<String, FormFieldPermission> permissions = getFieldPermissions(node);
        if (permissions.isEmpty()) {
            return formData; // No restrictions configured
        }
        Map<String, Object> filtered = new HashMap<>();
        for (Map.Entry<String, Object> entry : formData.entrySet()) {
            FormFieldPermission perm = permissions.get(entry.getKey());
            // HIDDEN and READONLY edits are dropped; editable / required / unspecified pass through.
            if (perm == FormFieldPermission.HIDDEN || perm == FormFieldPermission.READONLY) {
                continue;
            }
            filtered.put(entry.getKey(), entry.getValue());
        }
        return filtered;
    }
}
