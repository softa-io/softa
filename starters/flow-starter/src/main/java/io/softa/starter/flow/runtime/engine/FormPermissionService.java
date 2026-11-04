package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.Collection;
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
        return isEditable(getFieldPermissions(node).get(fieldName));
    }

    /** Check if a field is required. */
    public static boolean isFieldRequired(CompiledFlowNode node, String fieldName) {
        return getFieldPermissions(node).get(fieldName) == FormFieldPermission.REQUIRED;
    }

    /** The fields a node marks REQUIRED. */
    public static List<String> requiredFields(CompiledFlowNode node) {
        return getFieldPermissions(node).entrySet().stream()
                .filter(entry -> entry.getValue() == FormFieldPermission.REQUIRED)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Check if a field is hidden. */
    public static boolean isFieldHidden(CompiledFlowNode node, String fieldName) {
        return getFieldPermissions(node).get(fieldName) == FormFieldPermission.HIDDEN;
    }

    /**
     * Validate the values a node's REQUIRED fields will hold against node permissions: pass the
     * approver's edits over the values the row stores. A field holds no value when it is null, a blank
     * string or an empty collection — a table without a row, a multi-select with nothing picked.
     *
     * @return list of validation error messages (empty if valid)
     */
    public static List<String> validateFormData(CompiledFlowNode node, Map<String, Object> formData) {
        Map<String, FormFieldPermission> permissions = getFieldPermissions(node);
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, FormFieldPermission> entry : permissions.entrySet()) {
            if (entry.getValue() == FormFieldPermission.REQUIRED) {
                String fieldName = entry.getKey();
                if (isEmpty(formData == null ? null : formData.get(fieldName))) {
                    errors.add("Field '" + fieldName + "' is required");
                }
            }
        }
        return errors;
    }

    private static boolean isEmpty(Object value) {
        return value == null
                || value instanceof String s && s.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty();
    }

    /**
     * Filter form data down to the fields the node lets the approver edit: only EDITABLE and
     * REQUIRED entries pass. HIDDEN, READONLY and unlisted fields are dropped, and a node without
     * {@code formPermissions} passes nothing. The approval's writes run with permission checks set
     * aside, so this allow-list is the only authorization for the fields written. The write is a
     * <em>partial</em> update of the remaining (editable) fields, so dropped columns are simply left
     * untouched on the stored row — no original-row read is needed.
     */
    public static Map<String, Object> filterFormData(CompiledFlowNode node, Map<String, Object> formData) {
        if (formData == null) {
            return Collections.emptyMap();
        }
        Map<String, FormFieldPermission> permissions = getFieldPermissions(node);
        Map<String, Object> filtered = new HashMap<>();
        for (Map.Entry<String, Object> entry : formData.entrySet()) {
            if (isEditable(permissions.get(entry.getKey()))) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    private static boolean isEditable(FormFieldPermission perm) {
        return perm == FormFieldPermission.EDITABLE || perm == FormFieldPermission.REQUIRED;
    }
}
