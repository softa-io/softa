package io.softa.starter.flow.runtime.engine;

import java.lang.reflect.Array;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;

/**
 * Resolves approvers and vote thresholds from compiled approval node configuration.
 */
@Component
public class ApproverResolutionService {

    public static final String APPROVER_CONTEXT_KEY = "approverContext";
    public static final String INITIATOR_ID_KEY = "initiatorId";
    public static final String INITIATOR_MANAGER_ID_KEY = "initiatorManagerId";
    public static final String ROLE_APPROVERS_KEY = "roleApprovers";
    public static final String INITIATOR_DEPT_ID_KEY = "initiatorDeptId";

    @Autowired(required = false)
    private OrganizationService organizationService;

    public List<String> resolveApprovers(CompiledFlowNode node) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || cfg.getApprovers() == null) {
            return List.of();
        }
        return cfg.getApprovers().stream()
                .filter(Objects::nonNull)
                .filter(StringUtils::hasText)
                .toList();
    }

    public List<String> resolveApprovers(CompiledFlowNode node, Map<String, Object> variables) {
        if (!hasDynamicApproverSource(node)) {
            return resolveApprovers(node);
        }
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || cfg.getApproverSource() == null) {
            return resolveApprovers(node);
        }
        Map<String, Object> source = cfg.getApproverSource();
        String type = asString(source.get("type"));
        if (!StringUtils.hasText(type)) {
            return resolveApprovers(node);
        }

        Map<String, Object> resolutionVariables = new LinkedHashMap<>(variables == null ? Map.of() : variables);
        Object resolved = switch (type) {
            case "VariableList" -> lookupValue(resolutionVariables, asString(source.get("variable")));
            case "Expression" -> resolveExpression(resolutionVariables, asString(source.get("expression")));
            case "InitiatorManager" -> lookupValue(resolutionVariables, INITIATOR_MANAGER_ID_KEY);
            case "Role" -> resolveRoleApprovers(resolutionVariables, asString(source.get("roleCode")));
            case "Supervisor" -> resolveSupervisor(resolutionVariables, source);
            case "DeptLeader" -> resolveDeptLeader(resolutionVariables, source);
            case "RoleQuery" -> resolveRoleQuery(resolutionVariables, source);
            case "Position" -> resolvePosition(source);
            case "Department" -> resolveDepartment(resolutionVariables, source);
            default -> null;
        };
        return normalizeApprovers(resolved);
    }

    public boolean hasDynamicApproverSource(CompiledFlowNode node) {
        return node != null
                && node.getParsedConfig() instanceof ApprovalNodeConfig cfg
                && cfg.getApproverSource() != null;
    }

    public VoteThresholdMode resolveApprovalMode(CompiledFlowNode node) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || cfg.getApprovalMode() == null) {
            return VoteThresholdMode.ANY_ONE;
        }
        return cfg.getApprovalMode();
    }

    public int resolveRequiredApprovalCount(CompiledFlowNode node, List<String> approvers) {
        VoteThresholdMode approvalMode = resolveApprovalMode(node);
        ApprovalNodeConfig cfg = node.getParsedConfig() instanceof ApprovalNodeConfig c ? c : null;
        return switch (approvalMode) {
            case ANY_ONE -> 1;
            case UNANIMOUS -> approvers.isEmpty() ? 1 : approvers.size();
            case MIN_COUNT -> cfg != null && cfg.getMinCount() != null ? cfg.getMinCount() : 0;
            case PERCENTAGE -> {
                int pct = cfg != null && cfg.getPercentage() != null ? cfg.getPercentage() : 0;
                yield (int) Math.ceil((approvers.size() * pct) / 100.0d);
            }
        };
    }

    public VoteThresholdMode resolveRejectMode(CompiledFlowNode node) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg) || cfg.getRejectMode() == null) {
            return VoteThresholdMode.ANY_ONE;
        }
        return cfg.getRejectMode();
    }

    public int resolveRequiredRejectCount(CompiledFlowNode node, List<String> approvers) {
        VoteThresholdMode rejectMode = resolveRejectMode(node);
        ApprovalNodeConfig cfg = node.getParsedConfig() instanceof ApprovalNodeConfig c ? c : null;
        return switch (rejectMode) {
            case ANY_ONE -> 1;
            case UNANIMOUS -> approvers.isEmpty() ? 1 : approvers.size();
            case MIN_COUNT -> cfg != null && cfg.getRejectMinCount() != null ? cfg.getRejectMinCount() : 0;
            case PERCENTAGE -> {
                int pct = cfg != null && cfg.getRejectPercentage() != null ? cfg.getRejectPercentage() : 0;
                yield (int) Math.ceil((approvers.size() * pct) / 100.0d);
            }
        };
    }

    // ── Org-aware resolution methods ────────────────────────────────────

    private Object resolveSupervisor(Map<String, Object> variables, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        Long initiatorId = resolveInitiatorIdAsLong(variables);
        if (initiatorId == null) {
            return null;
        }
        int level = parseIntOrDefault(source.get("level"), 1);
        ApproverInfo supervisor = organizationService.getSupervisor(initiatorId, level);
        return supervisor == null ? null : supervisor.getUserIdAsString();
    }

    private Object resolveDeptLeader(Map<String, Object> variables, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        Long deptId = parseLong(source.get("deptId"));
        if (deptId == null) {
            deptId = resolveInitiatorDeptId(variables);
        }
        if (deptId == null) {
            return null;
        }
        ApproverInfo leader = organizationService.getDeptLeader(deptId);
        return leader == null ? null : leader.getUserIdAsString();
    }

    private Object resolveRoleQuery(Map<String, Object> variables, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        List<Long> roleIds = parseLongList(source.get("roleIds"));
        if (roleIds == null || roleIds.isEmpty()) {
            return null;
        }
        List<ApproverInfo> users = organizationService.getUsersByRoleIds(roleIds);
        String deptScope = asString(source.get("deptScope"));
        if ("INITIATOR_DEPT".equals(deptScope)) {
            Long initiatorDeptId = resolveInitiatorDeptId(variables);
            if (initiatorDeptId != null) {
                users = users.stream()
                        .filter(u -> initiatorDeptId.equals(u.getDeptId()))
                        .toList();
            }
        } else if ("SPECIFIC_DEPT".equals(deptScope)) {
            Long deptId = parseLong(source.get("deptId"));
            if (deptId != null) {
                users = users.stream()
                        .filter(u -> deptId.equals(u.getDeptId()))
                        .toList();
            }
        }
        return users.stream().map(ApproverInfo::getUserIdAsString).toList();
    }

    private Object resolvePosition(Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        List<Long> positionIds = parseLongList(source.get("positionIds"));
        if (positionIds == null || positionIds.isEmpty()) {
            return null;
        }
        return organizationService.getUsersByPositionIds(positionIds).stream()
                .map(ApproverInfo::getUserIdAsString).toList();
    }

    private Object resolveDepartment(Map<String, Object> variables, Map<String, Object> source) {
        if (organizationService == null) {
            return null;
        }
        String deptScope = asString(source.get("deptScope"));
        List<Long> deptIds;
        if ("INITIATOR_DEPT".equals(deptScope)) {
            Long initiatorDeptId = resolveInitiatorDeptId(variables);
            deptIds = initiatorDeptId == null ? List.of() : List.of(initiatorDeptId);
        } else {
            deptIds = parseLongList(source.get("deptIds"));
        }
        if (deptIds == null || deptIds.isEmpty()) {
            return null;
        }
        boolean includeSubDepts = Boolean.TRUE.equals(source.get("includeSubDepts"));
        return organizationService.getUsersByDeptIds(deptIds, includeSubDepts).stream()
                .map(ApproverInfo::getUserIdAsString).toList();
    }

    // ── Org-context helpers ───────────────────────────────────────────────

    private Long resolveInitiatorIdAsLong(Map<String, Object> variables) {
        Object val = lookupValue(variables, INITIATOR_ID_KEY);
        return parseLong(val);
    }

    private Long resolveInitiatorDeptId(Map<String, Object> variables) {
        Object val = lookupValue(variables, INITIATOR_DEPT_ID_KEY);
        if (val != null) {
            Long deptId = parseLong(val);
            if (deptId != null) {
                return deptId;
            }
        }
        if (organizationService != null) {
            Long initiatorId = resolveInitiatorIdAsLong(variables);
            if (initiatorId != null) {
                return organizationService.getUserDeptId(initiatorId);
            }
        }
        return null;
    }

    private Long parseLong(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number n) {
            return n.longValue();
        }
        String s = val.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseIntOrDefault(Object val, int defaultValue) {
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private List<Long> parseLongList(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Collection<?> coll) {
            return coll.stream()
                    .map(this::parseLong)
                    .filter(Objects::nonNull)
                    .toList();
        }
        return null;
    }

    // ── Existing resolution methods ───────────────────────────────────────

    private Object resolveExpression(Map<String, Object> variables, String expression) {
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        return ComputeUtils.execute(expression, new LinkedHashMap<>(variables));
    }

    @SuppressWarnings("unchecked")
    private Object resolveRoleApprovers(Map<String, Object> variables, String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            return null;
        }
        Object roleApprovers = lookupValue(variables, ROLE_APPROVERS_KEY);
        if (!(roleApprovers instanceof Map<?, ?> map)) {
            return null;
        }
        return ((Map<String, Object>) map).get(roleCode);
    }

    @SuppressWarnings("unchecked")
    private Object lookupValue(Map<String, Object> variables, String key) {
        if (!StringUtils.hasText(key) || variables == null || variables.isEmpty()) {
            return null;
        }
        if (variables.containsKey(key)) {
            return variables.get(key);
        }
        Object approverContext = variables.get(APPROVER_CONTEXT_KEY);
        if (approverContext instanceof Map<?, ?> context && context.containsKey(key)) {
            return ((Map<String, Object>) context).get(key);
        }
        if (!key.contains(".")) {
            return null;
        }

        Object current = variables;
        for (String part : key.split("\\.")) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = ((Map<String, Object>) currentMap).get(part);
        }
        return current;
    }

    private List<String> normalizeApprovers(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
        }
        if (value.getClass().isArray()) {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                Object item = Array.get(value, i);
                if (item != null && StringUtils.hasText(item.toString()) && !result.contains(item.toString())) {
                    result.add(item.toString());
                }
            }
            return List.copyOf(result);
        }
        String single = value.toString();
        if (!StringUtils.hasText(single)) {
            return List.of();
        }
        return List.of(single);
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
