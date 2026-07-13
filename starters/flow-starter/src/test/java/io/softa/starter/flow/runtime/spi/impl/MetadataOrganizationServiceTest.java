package io.softa.starter.flow.runtime.spi.impl;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;

import io.softa.starter.flow.runtime.spi.ApproverInfo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MetadataOrganizationService}.
 */
class MetadataOrganizationServiceTest {

    private ModelService<Long> modelService;
    private MetadataOrganizationService orgService;
    private OrganizationServiceProperties props;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        props = new OrganizationServiceProperties();
        orgService = new MetadataOrganizationService(modelService, props);
    }

    @Test
    void getUserByIdReturnsApproverInfo() {
        when(modelService.getById("EmpInfo", 100L))
                .thenReturn(Optional.of(Map.of("id", 100L, "name", "Alice", "deptId", 10L)));

        ApproverInfo info = orgService.getUserById(100L);

        assertNotNull(info);
        assertEquals(100L, info.getUserId());
        assertEquals("Alice", info.getUserName());
        assertEquals(10L, info.getDeptId());
    }

    @Test
    void getUserByIdReturnsNullWhenNotFound() {
        when(modelService.getById("EmpInfo", 999L)).thenReturn(Optional.empty());

        assertNull(orgService.getUserById(999L));
    }

    @Test
    void getUsersByIdsReturnsList() {
        when(modelService.getByIds(eq("EmpInfo"), eq(List.of(1L, 2L)), isNull()))
                .thenReturn(List.of(
                        Map.of("id", 1L, "name", "A", "deptId", 10L),
                        Map.of("id", 2L, "name", "B", "deptId", 20L)));

        List<ApproverInfo> result = orgService.getUsersByIds(List.of(1L, 2L));

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getUserId());
        assertEquals(2L, result.get(1).getUserId());
    }

    @Test
    void getSupervisorTraversesLevels() {
        // Level 1: user 100 -> manager 200
        when(modelService.getById("EmpInfo", 100L))
                .thenReturn(Optional.of(Map.of("id", 100L, "name", "Junior", "managerId", 200L, "deptId", 10L)));
        // Level 2: user 200 -> manager 300
        when(modelService.getById("EmpInfo", 200L))
                .thenReturn(Optional.of(Map.of("id", 200L, "name", "Manager", "managerId", 300L, "deptId", 10L)));
        // Fetch user 300
        when(modelService.getById("EmpInfo", 300L))
                .thenReturn(Optional.of(Map.of("id", 300L, "name", "Director", "deptId", 10L)));

        ApproverInfo supervisor = orgService.getSupervisor(100L, 2);

        assertNotNull(supervisor);
        assertEquals(300L, supervisor.getUserId());
        assertEquals("Director", supervisor.getUserName());
    }

    @Test
    void getSupervisorReturnsNullWhenChainBreaks() {
        when(modelService.getById("EmpInfo", 100L))
                .thenReturn(Optional.of(Map.of("id", 100L, "name", "CEO")));
        // No managerId field -> null

        assertNull(orgService.getSupervisor(100L, 1));
    }

    @Test
    void getDeptLeaderQueriesDeptModel() {
        when(modelService.getById("DeptInfo", 10L))
                .thenReturn(Optional.of(Map.of("id", 10L, "name", "Engineering", "leaderId", 200L)));
        when(modelService.getById("EmpInfo", 200L))
                .thenReturn(Optional.of(Map.of("id", 200L, "name", "Leader", "deptId", 10L)));

        ApproverInfo leader = orgService.getDeptLeader(10L);

        assertNotNull(leader);
        assertEquals(200L, leader.getUserId());
        assertEquals("Leader", leader.getUserName());
    }

    @Test
    void getUserDeptIdReturnsCorrectDeptId() {
        when(modelService.getById(eq("EmpInfo"), eq(100L), any(Collection.class)))
                .thenReturn(Optional.of(Map.of("deptId", 10L)));

        Long deptId = orgService.getUserDeptId(100L);

        assertEquals(10L, deptId);
    }

    @Test
    void getSubDeptIdsReturnsFlatList() {
        // Dept 10 has children 11, 12
        when(modelService.searchList(eq("DeptInfo"), argThat(q -> {
            var fu = q.getFilters().getFilterUnit();
            return fu != null && "parentId".equals(fu.getField()) && Long.valueOf(10L).equals(fu.getValue());
        }))).thenReturn(List.of(
                Map.of("id", 11L),
                Map.of("id", 12L)));

        // Dept 11 has child 111
        when(modelService.searchList(eq("DeptInfo"), argThat(q -> {
            var fu = q.getFilters().getFilterUnit();
            return fu != null && "parentId".equals(fu.getField()) && Long.valueOf(11L).equals(fu.getValue());
        }))).thenReturn(List.of(Map.of("id", 111L)));

        // Dept 12, 111 have no children
        when(modelService.searchList(eq("DeptInfo"), argThat(q -> {
            var fu = q.getFilters().getFilterUnit();
            return fu != null && "parentId".equals(fu.getField())
                    && (Long.valueOf(12L).equals(fu.getValue()) || Long.valueOf(111L).equals(fu.getValue()));
        }))).thenReturn(List.of());

        List<Long> subDeptIds = orgService.getSubDeptIds(10L);

        assertEquals(3, subDeptIds.size());
        assertTrue(subDeptIds.containsAll(List.of(11L, 12L, 111L)));
    }

    @Test
    void getUsersByRoleIdsQueriesRoleModel() {
        when(modelService.searchList(eq("EmpRole"), any(FlexQuery.class)))
                .thenReturn(List.of(
                        Map.of("empId", 100L),
                        Map.of("empId", 200L)));
        when(modelService.getByIds(eq("EmpInfo"), eq(List.of(100L, 200L)), isNull()))
                .thenReturn(List.of(
                        Map.of("id", 100L, "name", "A", "deptId", 10L),
                        Map.of("id", 200L, "name", "B", "deptId", 20L)));

        List<ApproverInfo> result = orgService.getUsersByRoleIds(List.of(1L));

        assertEquals(2, result.size());
    }

    @Test
    void getUsersByDeptIdsWithoutSubDepts() {
        when(modelService.searchList(eq("EmpInfo"), any(FlexQuery.class)))
                .thenReturn(List.of(
                        Map.of("id", 100L, "name", "A", "deptId", 10L)));

        List<ApproverInfo> result = orgService.getUsersByDeptIds(List.of(10L), false);

        assertEquals(1, result.size());
        assertEquals(100L, result.getFirst().getUserId());
    }

    @Test
    void getUsersByPositionIdsFiltersCorrectly() {
        when(modelService.searchList(eq("EmpInfo"), any(FlexQuery.class)))
                .thenReturn(List.of(
                        Map.of("id", 100L, "name", "A", "deptId", 10L)));

        List<ApproverInfo> result = orgService.getUsersByPositionIds(List.of(5L));

        assertEquals(1, result.size());
    }

    @Test
    void nullInputsReturnEmptyOrNull() {
        assertNull(orgService.getUserById(null));
        assertEquals(List.of(), orgService.getUsersByIds(null));
        assertEquals(List.of(), orgService.getUsersByIds(List.of()));
        assertEquals(List.of(), orgService.getUsersByRoleIds(null));
        assertEquals(List.of(), orgService.getUsersByDeptIds(null, false));
        assertEquals(List.of(), orgService.getUsersByPositionIds(null));
        assertNull(orgService.getSupervisor(null, 1));
        assertNull(orgService.getSupervisor(100L, 0));
        assertNull(orgService.getDeptLeader(null));
        assertNull(orgService.getUserDeptId(null));
        assertEquals(List.of(), orgService.getSubDeptIds(null));
    }
}
