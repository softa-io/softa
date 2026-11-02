package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportDataDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.enums.ImportRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An employee file whose rows name their manager by a code another row of the same file carries.
 *
 * <p>The lookup only sees stored rows, and no row of a file is written before every lookup has run, so
 * a manager listed in the same file was "not found" whatever the order of the rows: a chain three levels
 * deep took three imports, or an import with the managers left blank and a second one to fill them in.
 */
class InFileSelfReferenceImportTest {

    private static final String MODEL = "Employee";

    private static final RelationLookupResolver.LookupGroup MANAGER = new RelationLookupResolver.LookupGroup(
            "piEmployeeId", MODEL, List.of("code"), List.of("piEmployeeId.code"), true, false, false, null);

    private static Map<String, Object> sheetRow(String code, String managerCode) {
        Map<String, Object> row = new HashMap<>();
        row.put("code", code);
        if (managerCode != null) {
            row.put("piEmployeeId.code", managerCode);
        }
        return row;
    }

    @SuppressWarnings("unchecked")
    private static RelationLookupResolver resolverWithStored(Map<List<Object>, Long> stored) {
        RelationLookupResolver resolver = new RelationLookupResolver();
        ModelService<Long> modelService = mock(ModelService.class);
        when(modelService.getIdsByBusinessKeys(eq(MODEL), eq(List.of("code")), anyCollection(), any()))
                .thenReturn(stored);
        ReflectionTestUtils.setField(resolver, "modelService", modelService);
        return resolver;
    }

    @Test
    void aManagerInTheSameFileIsAReferenceToFillNotAFailure() {
        Map<String, Object> staff = sheetRow("E2", "E1");
        Map<String, Object> head = sheetRow("E1", null);
        Map<String, Object> stored = sheetRow("E3", "E0");
        Map<String, Object> unknown = sheetRow("E4", "E404");

        List<InFileReferences.Reference> references = resolverWithStored(Map.of(List.of("E0"), 700L))
                .resolveRows(MODEL, new ArrayList<>(List.of(staff, head, stored, unknown)), List.of(MANAGER), true);

        assertThat(references).singleElement().satisfies(reference -> {
            assertThat(reference.row()).isSameAs(staff);
            assertThat(reference.target()).isSameAs(head);
            assertThat(reference.key()).containsExactly("E1");
        });
        assertThat(staff).doesNotContainKey(FileConstant.FAILED_REASON).doesNotContainKey("piEmployeeId");
        // A manager already stored resolves as it always did; one found nowhere still fails.
        assertThat(stored.get("piEmployeeId")).isEqualTo(700L);
        assertThat(unknown.get(FileConstant.FAILED_REASON)).isEqualTo("Employee not found: E404");
    }

    @Test
    void aRowCannotBeItsOwnManager() {
        Map<String, Object> self = sheetRow("E1", "E1");

        List<InFileReferences.Reference> references = resolverWithStored(Map.of())
                .resolveRows(MODEL, new ArrayList<>(List.of(self)), List.of(MANAGER), true);

        assertThat(references).isEmpty();
        assertThat((String) self.get(FileConstant.FAILED_REASON)).endsWith("cannot be the row itself.");
    }

    @Test
    void onlyAReferenceOntoTheImportedModelItselfIsDeferred() {
        // A department code on an employee row names another model; this file cannot supply it.
        var department = new RelationLookupResolver.LookupGroup(
                "departmentId", "Department", List.of("code"), List.of("departmentId.code"), true, false, false, null);

        assertThat(RelationLookupResolver.isSelfReference(MODEL, MANAGER)).isTrue();
        assertThat(RelationLookupResolver.isSelfReference(MODEL, department)).isFalse();
        assertThat(RelationLookupResolver.isSelfReference(null, MANAGER)).isFalse();
    }

    /** A row after the lookup: its own code, and the reference to fill. */
    private static Map<String, Object> resolvedRow(String code) {
        Map<String, Object> row = new HashMap<>();
        row.put("code", code);
        return row;
    }

    private static ImportTemplateDTO template(boolean skipException) {
        ImportTemplateDTO template = new ImportTemplateDTO();
        template.setModelName(MODEL);
        template.setImportRule(ImportRule.ONLY_CREATE);
        template.setSkipException(skipException);
        return template;
    }

    private static ImportDataDTO data(List<Map<String, Object>> rows, List<InFileReferences.Reference> references) {
        ImportDataDTO data = new ImportDataDTO();
        data.setRows(new ArrayList<>(rows));
        List<Map<String, Object>> originals = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            originals.add(new HashMap<>(row));
        }
        data.setOriginalRows(originals);
        data.setFailedRows(new ArrayList<>());
        data.setInFileReferences(new ArrayList<>(references));
        return data;
    }

    private static InFileReferences.Reference manager(Map<String, Object> row, Map<String, Object> target) {
        return new InFileReferences.Reference(row, "piEmployeeId", "Manager", List.of("code"),
                List.of(target.get("code")), target);
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesTheManagerFirstAndTheStaffWithItsIdAlreadySet() {
        ModelService<?> modelService = mock(ModelService.class);
        List<List<Map<String, Object>>> writes = new ArrayList<>();
        AtomicLong ids = new AtomicLong(100);
        doAnswer(invocation -> {
            List<Map<String, Object>> rows = invocation.getArgument(1);
            List<Map<String, Object>> seen = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                seen.add(new HashMap<>(row));
                row.put("id", ids.incrementAndGet());
            }
            writes.add(seen);
            return List.of();
        }).when(modelService).createList(eq(MODEL), anyList());
        ImportPersistenceService service = new ImportPersistenceService();
        ReflectionTestUtils.setField(service, "modelService", modelService);

        Map<String, Object> staff = resolvedRow("E3");
        Map<String, Object> lead = resolvedRow("E2");
        Map<String, Object> head = resolvedRow("E1");
        ImportDataDTO data = data(List.of(staff, lead, head), List.of(manager(staff, lead), manager(lead, head)));

        var result = service.persist(template(true), data);

        assertThat(writes).hasSize(3);
        assertThat(writes.get(0)).singleElement().satisfies(row -> assertThat(row.get("code")).isEqualTo("E1"));
        assertThat(writes.get(1)).singleElement().satisfies(row -> assertThat(row.get("piEmployeeId")).isEqualTo(101L));
        assertThat(writes.get(2)).singleElement().satisfies(row -> assertThat(row.get("piEmployeeId")).isEqualTo(102L));
        assertThat(result.created()).hasSize(3);
        assertThat(data.getRows()).hasSize(3);
        assertThat(data.getFailedRows()).isEmpty();
    }

    @Test
    void aStaffRowWhoseManagerFailedToWriteFailsWithThatReason() {
        ModelService<?> modelService = mock(ModelService.class);
        doAnswer(invocation -> {
            throw new IllegalStateException("duplicate key");
        }).when(modelService).createList(eq(MODEL), anyList());
        ImportPersistenceService service = new ImportPersistenceService();
        ReflectionTestUtils.setField(service, "modelService", modelService);

        Map<String, Object> staff = resolvedRow("E2");
        Map<String, Object> head = resolvedRow("E1");
        ImportDataDTO data = data(List.of(staff, head), List.of(manager(staff, head)));

        service.persist(template(true), data);

        assertThat(data.getRows()).isEmpty();
        assertThat(data.getFailedRows()).hasSize(2);
        assertThat(data.getFailedRows())
                .extracting(row -> row.get(FileConstant.FAILED_REASON))
                .contains("Manager E1 is a row of this file that was not imported.");
    }

    @Test
    void aFailFastFileStopsWhenTheRowsItNamesDidNotGoIn() {
        ModelService<?> modelService = mock(ModelService.class);
        doAnswer(invocation -> List.of()).when(modelService).createList(eq(MODEL), anyList());
        ImportPersistenceService service = new ImportPersistenceService();
        ReflectionTestUtils.setField(service, "modelService", modelService);

        // The manager row is written without an id coming back, so nothing can be filled from it.
        Map<String, Object> staff = resolvedRow("E2");
        Map<String, Object> head = resolvedRow("E1");
        ImportDataDTO data = data(List.of(staff, head), List.of(manager(staff, head)));

        assertThatThrownBy(() -> service.persist(template(false), data))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Manager E1 is a row of this file that was not imported.");
    }

    @Test
    void aFileWithNoInFileReferencesIsWrittenInOneGo() {
        ModelService<?> modelService = mock(ModelService.class);
        List<Integer> batchSizes = new ArrayList<>();
        doAnswer(invocation -> {
            List<Map<String, Object>> rows = invocation.getArgument(1);
            batchSizes.add(rows.size());
            return List.of();
        }).when(modelService).createList(eq(MODEL), anyList());
        ImportPersistenceService service = new ImportPersistenceService();
        ReflectionTestUtils.setField(service, "modelService", modelService);

        service.persist(template(true), data(List.of(resolvedRow("E1"), resolvedRow("E2")), List.of()));

        assertThat(batchSizes).containsExactly(2);
    }
}
