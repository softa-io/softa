package io.softa.framework.orm.service.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.versioning.VersioningStrategy;
import io.softa.framework.orm.service.versioning.VersioningStrategyResolver;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An update is judged twice: on the rows as they were, before the write, and on the rows as written,
 * after it. The second check is what stops an update from moving a row out of the caller's range.
 */
class UpdateRowScopeRecheckTest {

    private static final String MODEL = "Employee";

    private final PermissionService permissionService = mock(PermissionService.class);
    @SuppressWarnings("unchecked")
    private final JdbcService<Long> jdbcService = mock(JdbcService.class);
    private final VersioningStrategyResolver versioning = mock(VersioningStrategyResolver.class);
    private final VersioningStrategy strategy = mock(VersioningStrategy.class);
    private final ModelServiceImpl<Long> service = new ModelServiceImpl<>();
    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(service, "versioning", versioning);
        when(versioning.of(anyString())).thenReturn(strategy);
        when(permissionService.getWriteGuardedFields(MODEL)).thenReturn(Set.of());
        when(strategy.resolveTargetIds(eq(MODEL), anyList())).thenReturn(List.<Serializable>of(1L));
        when(strategy.update(eq(MODEL), anyList(), anySet())).thenReturn(1);

        models = Mockito.mockStatic(ModelManager.class);
        MetaField pk = new MetaField();
        ReflectionTestUtils.setField(pk, "fieldName", "id");
        ReflectionTestUtils.setField(pk, "fieldType", FieldType.LONG);
        models.when(() -> ModelManager.getModelPrimaryKeyField(MODEL)).thenReturn(pk);
        models.when(() -> ModelManager.getModelPrimaryKey(MODEL)).thenReturn("id");
        models.when(() -> ModelManager.getModelUpdatableFields(MODEL))
                .thenAnswer(inv -> new HashSet<>(Set.of("deptId")));
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    private void moveToAnotherDepartment() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("deptId", 99L);
        ContextHolder.runWith(new Context(), () -> service.updateList(MODEL, new ArrayList<>(List.of(row))));
    }

    @Test
    void theRowsAreJudgedAgainAsWritten() {
        moveToAnotherDepartment();

        InOrder order = inOrder(permissionService, strategy);
        order.verify(permissionService).checkIdsFieldsAccess(eq(MODEL), eq(List.of(1L)), anySet(), eq(AccessType.UPDATE));
        order.verify(strategy).update(eq(MODEL), anyList(), anySet());
        order.verify(permissionService).checkIdsAccess(MODEL, List.of(1L), AccessType.UPDATE);
    }

    @Test
    void aRowWrittenOutOfRangeIsRefused() {
        doThrow(new PermissionException("out of scope"))
                .when(permissionService).checkIdsAccess(MODEL, List.of(1L), AccessType.UPDATE);

        assertThatThrownBy(this::moveToAnotherDepartment).isInstanceOf(PermissionException.class);
    }
}
