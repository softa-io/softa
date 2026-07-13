package io.softa.starter.flow.runtime.spi.impl;

import java.util.*;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;

import io.softa.starter.flow.runtime.spi.ApproverInfo;
import io.softa.starter.flow.runtime.spi.OrganizationService;

/**
 * Default {@link OrganizationService} implementation backed by the metadata-driven
 * {@link ModelService}. Queries organizational data from configurable metadata models.
 *
 * <p>Only activated when no application-level {@code OrganizationService} bean is present
 * and {@code ModelService} is available.</p>
 *
 * <p>Model names and field mappings are configured via {@link OrganizationServiceProperties}.</p>
 */
@Slf4j
public class MetadataOrganizationService implements OrganizationService {

    private final ModelService<Long> modelService;
    private final OrganizationServiceProperties props;

    @SuppressWarnings("unchecked")
    public MetadataOrganizationService(ModelService<?> modelService, OrganizationServiceProperties props) {
        this.modelService = (ModelService<Long>) modelService;
        this.props = props;
    }

    @Override
    public ApproverInfo getUserById(Long userId) {
        if (userId == null) {
            return null;
        }
        Optional<Map<String, Object>> row = modelService.getById(props.getEmployeeModel(), userId);
        return row.map(this::toApproverInfo).orElse(null);
    }

    @Override
    public List<ApproverInfo> getUsersByIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = modelService.getByIds(props.getEmployeeModel(), userIds, null);
        return rows.stream().map(this::toApproverInfo).filter(Objects::nonNull).toList();
    }

    @Override
    public List<ApproverInfo> getUsersByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        Filters filters = Filters.of(props.getRoleIdField(), Operator.IN, roleIds);
        FlexQuery query = new FlexQuery();
        query.setFields(List.of(props.getRoleUserIdField()));
        query.setFilters(filters);
        List<Map<String, Object>> roleRows = modelService.searchList(props.getRoleModel(), query);

        List<Long> userIds = roleRows.stream()
                .map(row -> toLong(row.get(props.getRoleUserIdField())))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return getUsersByIds(userIds);
    }

    @Override
    public List<ApproverInfo> getUsersByDeptIds(List<Long> deptIds, boolean includeSubDepts) {
        if (deptIds == null || deptIds.isEmpty()) {
            return List.of();
        }
        List<Long> targetDeptIds;
        if (includeSubDepts) {
            Set<Long> allDeptIds = new HashSet<>(deptIds);
            for (Long deptId : deptIds) {
                allDeptIds.addAll(getSubDeptIds(deptId));
            }
            targetDeptIds = new ArrayList<>(allDeptIds);
        } else {
            targetDeptIds = deptIds;
        }
        Filters filters = Filters.of(props.getDeptIdField(), Operator.IN, targetDeptIds);
        FlexQuery query = new FlexQuery();
        query.setFilters(filters);
        List<Map<String, Object>> rows = modelService.searchList(props.getEmployeeModel(), query);
        return rows.stream().map(this::toApproverInfo).filter(Objects::nonNull).toList();
    }

    @Override
    public List<ApproverInfo> getUsersByPositionIds(List<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return List.of();
        }
        Filters filters = Filters.of(props.getPositionIdField(), Operator.IN, positionIds);
        FlexQuery query = new FlexQuery();
        query.setFilters(filters);
        List<Map<String, Object>> rows = modelService.searchList(props.getEmployeeModel(), query);
        return rows.stream().map(this::toApproverInfo).filter(Objects::nonNull).toList();
    }

    @Override
    public ApproverInfo getSupervisor(Long userId, int level) {
        if (userId == null || level < 1) {
            return null;
        }
        Long currentId = userId;
        for (int i = 0; i < level; i++) {
            Optional<Map<String, Object>> row = modelService.getById(props.getEmployeeModel(), currentId);
            if (row.isEmpty()) {
                return null;
            }
            Long managerId = toLong(row.get().get(props.getSupervisorIdField()));
            if (managerId == null) {
                return null;
            }
            currentId = managerId;
        }
        return getUserById(currentId);
    }

    @Override
    public ApproverInfo getDeptLeader(Long deptId) {
        if (deptId == null) {
            return null;
        }
        Optional<Map<String, Object>> deptRow = modelService.getById(props.getDepartmentModel(), deptId);
        if (deptRow.isEmpty()) {
            return null;
        }
        Long leaderId = toLong(deptRow.get().get(props.getDeptLeaderIdField()));
        if (leaderId == null) {
            return null;
        }
        return getUserById(leaderId);
    }

    @Override
    public Long getUserDeptId(Long userId) {
        if (userId == null) {
            return null;
        }
        Optional<Map<String, Object>> row = modelService.getById(props.getEmployeeModel(), userId,
                List.of(props.getDeptIdField()));
        return row.map(r -> toLong(r.get(props.getDeptIdField()))).orElse(null);
    }

    @Override
    public List<Long> getSubDeptIds(Long deptId) {
        if (deptId == null) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        Queue<Long> queue = new LinkedList<>();
        queue.add(deptId);
        int depth = 0;
        while (!queue.isEmpty() && depth < props.getMaxDeptDepth()) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                Long currentDeptId = queue.poll();
                Filters filters = Filters.of(props.getParentDeptIdField(), Operator.EQUAL, currentDeptId);
                FlexQuery query = new FlexQuery();
                query.setFields(List.of(props.getUserIdField()));
                query.setFilters(filters);
                List<Map<String, Object>> children = modelService.searchList(props.getDepartmentModel(), query);
                for (Map<String, Object> child : children) {
                    Long childId = toLong(child.get(props.getUserIdField()));
                    if (childId != null && !childId.equals(deptId) && !result.contains(childId)) {
                        result.add(childId);
                        queue.add(childId);
                    }
                }
            }
            depth++;
        }
        return Collections.unmodifiableList(result);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private ApproverInfo toApproverInfo(Map<String, Object> row) {
        Long userId = toLong(row.get(props.getUserIdField()));
        if (userId == null) {
            return null;
        }
        String name = row.get(props.getNameField()) != null ? row.get(props.getNameField()).toString() : null;
        Long deptId = toLong(row.get(props.getDeptIdField()));
        // deptName is not always on the employee row; leave null if absent
        String deptName = row.get(props.getDeptNameField()) != null
                && !props.getDeptNameField().equals(props.getNameField())
                ? row.get(props.getDeptNameField()).toString() : null;
        return new ApproverInfo(userId, name, deptId, deptName);
    }

    private Long toLong(Object val) {
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
}
