package io.softa.framework.orm.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.versioning.VersioningStrategy;
import io.softa.framework.orm.service.versioning.VersioningStrategyResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which name search keeps the caller's row range, and which one crosses it.
 *
 * <p>{@code searchName} is what {@code /{model}/searchName} and its typed shadows call, so its rows are
 * the candidates a picker offers. It once crossed the row range like the expansion read does, and a
 * picker restricted to one department listed every employee in the tenant — choosable, then refused by
 * the detail read with "outside your READ scope". The crossing variant is for the read pipeline only.
 */
class SearchNameRowScopeTest {

    private final PermissionService permissionService = mock(PermissionService.class);
    @SuppressWarnings("unchecked")
    private final JdbcService<Long> jdbcService = mock(JdbcService.class);
    private final VersioningStrategyResolver versioning = mock(VersioningStrategyResolver.class);
    private final VersioningStrategy strategy = mock(VersioningStrategy.class);

    private final ModelServiceImpl<Long> service = new ModelServiceImpl<>();

    private static final String MODEL = "Employee";

    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void wire() {
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.getModelDisplayName(MODEL)).thenReturn(List.of("code", "name"));

        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(service, "versioning", versioning);

        when(versioning.of(anyString())).thenReturn(strategy);
        when(strategy.scopeRead(anyString(), any(FlexQuery.class))).thenAnswer(i -> new Filters());
        when(permissionService.filterReadableFields(anyString(), any(), eq(AccessType.READ)))
                .thenAnswer(i -> i.getArgument(1));
        when(permissionService.appendScopeAccessFilters(anyString(), any(Filters.class)))
                .thenAnswer(i -> i.getArgument(1));
        when(permissionService.appendScopeAccessFiltersIgnoringRowScope(anyString(), any(Filters.class)))
                .thenAnswer(i -> i.getArgument(1));
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new HashMap<>(Map.of("id", 1L, "code", "1231", "name", "E1")));
        when(jdbcService.selectByFilter(anyString(), any(FlexQuery.class))).thenReturn(rows);
    }

    @AfterEach
    void close() {
        modelManager.close();
    }

    @Test
    void searchName_appendsTheCallersRowRange() {
        service.searchName(MODEL, new FlexQuery(new Filters()));

        verify(permissionService).appendScopeAccessFilters(eq(MODEL), any(Filters.class));
        verify(permissionService, never()).appendScopeAccessFiltersIgnoringRowScope(anyString(), any(Filters.class));
    }

    @Test
    void searchNameIgnoringRowScope_doesNotAppendTheCallersRowRange() {
        service.searchNameIgnoringRowScope(MODEL, new FlexQuery(new Filters()));

        verify(permissionService, never()).appendScopeAccessFilters(anyString(), any(Filters.class));
        verify(permissionService).appendScopeAccessFiltersIgnoringRowScope(eq(MODEL), any(Filters.class));
    }

    @Test
    void searchNameIgnoringRowScope_stillMasksTheResponse() {
        service.searchNameIgnoringRowScope(MODEL, new FlexQuery(new Filters()));

        verify(permissionService).checkModelFieldsAccess(eq(MODEL), any(), eq(AccessType.READ));
        verify(permissionService).maskResponseValue(eq(MODEL), any(), eq(AccessType.READ));
    }

    @Test
    void bothComposeTheSameDisplayName() {
        List<Map<String, Object>> scoped = service.searchName(MODEL, new FlexQuery(new Filters()));
        List<Map<String, Object>> crossing = service.searchNameIgnoringRowScope(MODEL, new FlexQuery(new Filters()));

        assertThat(scoped).extracting(r -> r.get(ModelConstant.DISPLAY_NAME)).containsExactly("1231 / E1");
        assertThat(crossing).extracting(r -> r.get(ModelConstant.DISPLAY_NAME)).containsExactly("1231 / E1");
    }
}
