package io.softa.framework.orm.service.impl;

import java.time.LocalDate;
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
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.versioning.VersioningStrategy;
import io.softa.framework.orm.service.versioning.VersioningStrategyResolver;
import io.softa.framework.orm.vo.ModelReference;
import io.softa.framework.orm.vo.OptionReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A name search reads its display fields as references, so a relation or an option among them comes back as a
 * {@link ModelReference} or an {@link OptionReference}; the name it composes takes their names and labels.
 * Joined as they came, a salary item's version read "ModelReference(id=…, displayName=Basic Monthly Salary) /
 * 2026-10-01" in every picker and table that named it (#2315).
 */
class SearchNameDisplayValuesTest {

    private final PermissionService permissionService = mock(PermissionService.class);
    @SuppressWarnings("unchecked")
    private final JdbcService<Long> jdbcService = mock(JdbcService.class);
    private final VersioningStrategyResolver versioning = mock(VersioningStrategyResolver.class);
    private final VersioningStrategy strategy = mock(VersioningStrategy.class);

    private final ModelServiceImpl<Long> service = new ModelServiceImpl<>();

    private static final String MODEL = "EmpSalaryProfileItem";

    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void wire() {
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.getModelDisplayName(MODEL))
                .thenReturn(List.of("salaryProfileItemId", "effectiveStartDate", "currency"));

        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(service, "versioning", versioning);

        when(versioning.of(anyString())).thenReturn(strategy);
        when(strategy.scopeRead(anyString(), any(FlexQuery.class))).thenAnswer(i -> new Filters());
        when(permissionService.filterReadableFields(anyString(), any(), eq(AccessType.READ)))
                .thenAnswer(i -> i.getArgument(1));
        when(permissionService.appendScopeAccessFilters(anyString(), any(Filters.class)))
                .thenAnswer(i -> i.getArgument(1));
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new HashMap<>(Map.of(
                "id", 1L,
                "salaryProfileItemId", ModelReference.of(891754571066507286L, "Basic Monthly Salary"),
                "effectiveStartDate", LocalDate.of(2026, 10, 1),
                "currency", OptionReference.of("SGD", "Singapore Dollar"))));
        when(jdbcService.selectByPage(anyString(), any(FlexQuery.class), any()))
                .thenAnswer(i -> i.<Page<Map<String, Object>>>getArgument(2).setRows(rows));
    }

    @AfterEach
    void close() {
        modelManager.close();
    }

    @Test
    void theNameTakesTheNamesOfTheReferencesItIsMadeOf() {
        List<Map<String, Object>> found = service.searchName(MODEL, new FlexQuery(new Filters()), Page.ofCursorPage(10));

        assertThat(found).extracting(row -> row.get(ModelConstant.DISPLAY_NAME))
                .containsExactly("Basic Monthly Salary / 2026-10-01 / Singapore Dollar");
    }
}
