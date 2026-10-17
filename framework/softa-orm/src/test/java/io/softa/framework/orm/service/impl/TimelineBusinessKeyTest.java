package io.softa.framework.orm.service.impl;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A row without an id is not a row without an identity.
 *
 * <p>A slice belongs to an ENTITY, and a model that declares a businessKey has said what identifies
 * one. Before this, an id-less row always minted a fresh entity — so importing two effective dates
 * of one pay item for one employee produced two entities rather than two versions of one, both left
 * open-ended, and payroll reads both as current: the same amount paid twice.
 *
 * <p>The rows cannot carry the id themselves. The first row's entity does not exist until it is
 * written, and that happens here, one row at a time — which is why the lookup belongs at this point
 * and nowhere earlier.
 */
class TimelineBusinessKeyTest {

    private static final String MODEL = "EmpSalaryProfileItem";

    @Test
    @SuppressWarnings("unchecked")
    void anIdLessRowJoinsTheEntityItsBusinessKeyNames() {
        JdbcService<Serializable> jdbcService = mock(JdbcService.class);
        // A fresh mutable map per call: the production path writes into what it reads back.
        when(jdbcService.selectByFilter(anyString(), any(FlexQuery.class)))
                .thenAnswer(invocation -> List.of(new LinkedHashMap<>(Map.of(ModelConstant.ID, 900L))));
        when(jdbcService.exist(anyString(), any())).thenReturn(true);

        Map<String, Object> row = rowFor(2026, 1, 1);

        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            stubModel(models, List.of("employeeId", "salaryProfileItemId"));
            createSlices(jdbcService, row);
        }

        assertThat(row.get(ModelConstant.ID))
                .as("the second version of a pay item belongs to the entity the first one created")
                .isEqualTo(900L);
    }

    /**
     * And a model that names no businessKey keeps creating a new entity, which is every timeline
     * model today — so nothing changes until one opts in by saying what identifies it.
     */
    @Test
    @SuppressWarnings("unchecked")
    void withoutABusinessKeyAnIdLessRowStillStartsANewEntity() {
        JdbcService<Serializable> jdbcService = mock(JdbcService.class);
        Map<String, Object> row = rowFor(2026, 1, 1);

        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            stubModel(models, List.of());
            createSlices(jdbcService, row);
        }

        assertThat(row.get(ModelConstant.ID)).isNull();
        verify(jdbcService, never()).selectByFilter(anyString(), any(FlexQuery.class));
        verify(jdbcService).insertList(anyString(), any());
    }

    /**
     * Joining an existing entity is an update of it. An entity the key finds outside the caller's
     * update range is refused — not taken for "not found", which would mint a second entity under the
     * same key — and none of its slices is touched.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anEntityTheKeyFindsOutsideTheUpdateRangeIsRefused() {
        JdbcService<Serializable> jdbcService = mock(JdbcService.class);
        when(jdbcService.selectByFilter(anyString(), any(FlexQuery.class)))
                .thenAnswer(invocation -> List.of(new LinkedHashMap<>(Map.of(ModelConstant.ID, 900L))));
        when(jdbcService.exist(anyString(), any())).thenReturn(true);
        PermissionService permissionService = mock(PermissionService.class);
        doThrow(new PermissionException("out of scope"))
                .when(permissionService).checkIdsAccess(MODEL, List.of(900L), AccessType.UPDATE);

        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            stubModel(models, List.of("employeeId", "salaryProfileItemId"));
            assertThatThrownBy(() -> createSlices(jdbcService, permissionService, rowFor(2026, 1, 1)))
                    .isInstanceOf(PermissionException.class);
        }

        verify(jdbcService, never()).insertList(anyString(), any());
        verify(jdbcService, never()).updateList(anyString(), any(), any());
    }

    private void createSlices(JdbcService<Serializable> jdbcService, Map<String, Object> row) {
        createSlices(jdbcService, mock(PermissionService.class), row);
    }

    private void createSlices(JdbcService<Serializable> jdbcService, PermissionService permissionService,
                              Map<String, Object> row) {
        TimelineServiceImpl<Serializable> service = new TimelineServiceImpl<>();
        ReflectionTestUtils.setField(service, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        Context context = new Context();
        context.setEffectiveDate(LocalDate.of(2026, 8, 27));
        ContextHolder.runWith(context, () -> service.createSlices(MODEL, new ArrayList<>(List.of(row))));
    }

    private static void stubModel(MockedStatic<ModelManager> models, List<String> businessKey) {
        MetaModel meta = mock(MetaModel.class);
        when(meta.getBusinessKey()).thenReturn(businessKey);
        models.when(() -> ModelManager.getModel(MODEL)).thenReturn(meta);
    }

    private static Map<String, Object> rowFor(int year, int month, int day) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("employeeId", 7L);
        row.put("salaryProfileItemId", 42L);
        row.put(ModelConstant.EFFECTIVE_START_DATE, LocalDate.of(year, month, day));
        return row;
    }
}
