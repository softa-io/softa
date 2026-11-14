package io.softa.starter.file.excel.export.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.vo.PivotSpec;

/**
 * The exported sheet must carry the same pivot columns the screen shows, by the same rule: the
 * column set is the key model's whole domain ordered by group then label, the cell is the value, else
 * 0 in the row's own group and the empty mark in another's. Pinned here on the pure pieces and once
 * end to end with the three reads mocked.
 */
class PivotColumnsTest {

    private static PivotSpec fteSpec(boolean suffix) {
        PivotSpec spec = new PivotSpec();
        spec.setSource("DeptFteTypeStats");
        spec.setJoinField("deptFteStatsId");
        spec.setKeyField("employmentType");
        spec.setKeyModel("EmploymentType");
        spec.setKeyModelGroupField("country");
        spec.setRowGroupField("companyId");
        spec.setValueField("headcount");
        spec.setSuffixGroup(suffix);
        return spec;
    }

    @Test
    void columnsAreTheWholeKeySet_orderedByGroupThenLabel_suffixedOnRequest() {
        List<Map<String, Object>> keys = List.of(
                Map.of("id", "SG_PartTime", "displayName", "Part Time", "country", "SG"),
                Map.of("id", "NZ_Casual", "displayName", "Casual", "country", Map.of("id", "NZ", "displayName", "New Zealand")),
                Map.of("id", "SG_FullTime", "displayName", "Full Time", "country", "SG"));

        assertThat(PivotColumns.buildColumns(keys, "country", true)).extracting(PivotColumns.Column::label)
                .containsExactly("Casual (NZ)", "Full Time (SG)", "Part Time (SG)");
        assertThat(PivotColumns.buildColumns(keys, "country", false)).extracting(PivotColumns.Column::label)
                .containsExactly("Casual", "Full Time", "Part Time");
    }

    @Test
    void aCellIsTheValue_else0InTheRowsGroup_elseTheEmptyMark() {
        assertThat(PivotColumns.cell(8, "SG", "SG", "—")).isEqualTo(8);
        assertThat(PivotColumns.cell(null, "SG", "SG", "—")).isEqualTo(0);
        assertThat(PivotColumns.cell(null, "NZ", "SG", "—")).isEqualTo("—");
        // Unknown groups: the column is assumed to apply rather than blank the cell.
        assertThat(PivotColumns.cell(null, null, "SG", "—")).isEqualTo(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void appendsTheColumnsToEveryRow_readingTheRowGroupThroughItsCompany() {
        PivotColumns pivot = new PivotColumns();
        ModelService<Long> models = mock(ModelService.class);
        ReflectionTestUtils.setField(pivot, "modelService", models);
        // The key model: SG has Full Time, NZ has Casual.
        when(models.searchName(eq("EmploymentType"), any(FlexQuery.class), any())).thenReturn(List.of(
                Map.of("id", "SG_FullTime", "displayName", "Full Time", "country", "SG"),
                Map.of("id", "NZ_Casual", "displayName", "Casual", "country", "NZ")));
        // The long table: dept 1 has 8 full-timers, dept 2 has 2 casuals.
        when(models.searchList(eq("DeptFteTypeStats"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("deptFteStatsId", 1L, "employmentType", "SG_FullTime", "headcount", 8),
                Map.of("deptFteStatsId", 2L, "employmentType", "NZ_Casual", "headcount", 2)));
        // The rows' raw company ids, then the companies' countries.
        when(models.searchList(eq("DeptFteStats"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("id", 1L, "companyId", 100L), Map.of("id", 2L, "companyId", 200L)));
        when(models.searchList(eq("Company"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("id", 100L, "country", "SG"), Map.of("id", 200L, "country", "NZ")));

        List<String> headers = new ArrayList<>(List.of("Department", "Total"));
        List<List<Object>> table = new ArrayList<>(List.of(
                new ArrayList<>(List.of("总部", 12)), new ArrayList<>(List.of("ttt", 5))));
        List<Map<String, Object>> rows = List.of(row(1L), row(2L));

        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel(any())).thenReturn(true);
            mm.when(() -> ModelManager.existField(any(), any())).thenReturn(true);
            MetaField companyRef = new MetaField();
            ReflectionTestUtils.setField(companyRef, "fieldType", FieldType.MANY_TO_ONE);
            ReflectionTestUtils.setField(companyRef, "relatedModel", "Company");
            mm.when(() -> ModelManager.getModelField("DeptFteStats", "companyId")).thenReturn(companyRef);

            pivot.append("DeptFteStats", fteSpec(true), rows, headers, table);
        }

        assertThat(headers).containsExactly("Department", "Total", "Casual (NZ)", "Full Time (SG)");
        assertThat(table.get(0)).containsExactly("总部", 12, "—", 8);
        assertThat(table.get(1)).containsExactly("ttt", 5, 2, "—");
    }

    private static Map<String, Object> row(long id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        return row;
    }
}
