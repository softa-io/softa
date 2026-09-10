package io.softa.starter.file.excel.export.strategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.file.excel.export.support.ExportDataFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * A dynamically exported sheet carries the columns that were picked, in that order.
 *
 * <p>It did neither. The field list was read after the query, and {@code SelectBuilder} writes its
 * own working set back onto the flexQuery — {@code new HashSet<>(fields)}, plus the id and whatever
 * a dynamic field depends on. So the exported sheet came out in hash order, carrying an id column
 * nobody asked for, no matter how the fields had been picked.
 *
 * <p>The fetch is stubbed, but stubbed to do exactly that one thing: it is the whole defect.
 */
class DynamicExportColumnsTest {

    private static final String MODEL = "EmpAddress";

    private final ExportByDynamic strategy = new ExportByDynamic();

    private static MetaField field(String fieldName, String label) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(metaField, "label", label);
        return metaField;
    }

    /** Stub the fetch, and have it mangle the field list the way the real query builder does. */
    private void fetchReturning(List<Map<String, Object>> rows) {
        ExportDataFetcher fetcher = mock(ExportDataFetcher.class);
        doAnswer(invocation -> {
            FlexQuery flexQuery = invocation.getArgument(2);
            // What SelectBuilder.handleSelect leaves behind: its own set, in its own order, + the id.
            Set<String> working = new LinkedHashSet<>();
            working.add("id");
            working.add("employeeCode");
            working.add("type");
            working.add("code");
            flexQuery.setFields(working);
            return rows;
        }).when(fetcher).fetchRows(eq(MODEL), isNull(), any(FlexQuery.class));
        ReflectionTestUtils.setField(strategy, "exportDataFetcher", fetcher);
    }

    @Test
    void theSheetCarriesTheColumnsThatWerePicked_inThatOrder() {
        FlexQuery flexQuery = new FlexQuery();
        // A declared cascaded field (EmpAddress.employeeCode -> employeeId.code) is a plain field
        // name here; the ORM fills its value in during result formatting, like any dynamic field.
        flexQuery.setFields(List.of("code", "employeeCode", "type"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 9001L);
        row.put("code", "ADR001");
        row.put("employeeCode", "UN0001");
        row.put("type", "Residential");
        fetchReturning(List.of(row));
        List<String> headers = new ArrayList<>();

        List<List<Object>> table;
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getLastFieldOfCascaded(MODEL, "code"))
                    .thenReturn(field("code", "Address Code"));
            mm.when(() -> ModelManager.getLastFieldOfCascaded(MODEL, "employeeCode"))
                    .thenReturn(field("employeeCode", "Employee Code"));
            mm.when(() -> ModelManager.getLastFieldOfCascaded(MODEL, "type"))
                    .thenReturn(field("type", "Address Type"));
            // Answerable on purpose: a regression picks the id up, and this test should report that
            // column rather than a NullPointerException.
            mm.when(() -> ModelManager.getLastFieldOfCascaded(MODEL, "id"))
                    .thenReturn(field("id", "ID"));

            table = strategy.extractDataTableFromDB(MODEL, flexQuery, headers);
        }

        assertThat(headers).containsExactly("Address Code", "Employee Code", "Address Type");
        assertThat(table).singleElement().asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.list(Object.class))
                .as("the id is not among them — it was never picked")
                .containsExactly("ADR001", "UN0001", "Residential");
    }
}
