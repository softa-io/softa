package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.SpringContextUtils;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportDataDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.enums.ImportRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ImportRowPipelineTest {

    @Test
    void validateCustomHandlerContractAllowsInPlaceMutation() {
        ImportRowPipeline importRowPipeline = new ImportRowPipeline();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new LinkedHashMap<>(Map.of("name", "A")));
        rows.add(new LinkedHashMap<>(Map.of("name", "B")));
        List<Integer> snapshot = rows.stream().map(System::identityHashCode).toList();

        rows.getFirst().put("name", "Updated");

        assertDoesNotThrow(() -> importRowPipeline.validateCustomHandlerContract("handler", rows, 2, snapshot));
    }

    @Test
    void validateCustomHandlerContractRejectsRowReordering() {
        ImportRowPipeline importRowPipeline = new ImportRowPipeline();
        Map<String, Object> rowA = new LinkedHashMap<>(Map.of("name", "A"));
        Map<String, Object> rowB = new LinkedHashMap<>(Map.of("name", "B"));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(rowA, rowB));
        List<Integer> snapshot = rows.stream().map(System::identityHashCode).toList();

        rows.set(0, rowB);
        rows.set(1, rowA);

        assertThrows(IllegalArgumentException.class,
                () -> importRowPipeline.validateCustomHandlerContract("handler", rows, 2, snapshot));
    }

    @Test
    void validateCustomHandlerContractRejectsRowCountChanges() {
        ImportRowPipeline importRowPipeline = new ImportRowPipeline();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new LinkedHashMap<>(Map.of("name", "A")));
        List<Integer> snapshot = rows.stream().map(System::identityHashCode).toList();

        rows.add(new LinkedHashMap<>(Map.of("name", "B")));

        assertThrows(IllegalArgumentException.class,
                () -> importRowPipeline.validateCustomHandlerContract("handler", rows, 1, snapshot));
    }

    @ParameterizedTest
    @EnumSource(ImportRule.class)
    void everyRuleDropsAllButTheFirstRowSharingAKey(ImportRule importRule) {
        // The database pre-check only runs for ONLY_CREATE, and it was the only thing standing between
        // a repeated key and the database — so this had to be wired in outside that branch. Any rule
        // can be handed the same row twice, and under CREATE_OR_UPDATE the second one silently
        // overwrites the first rather than being rejected.
        ImportRowPipeline pipeline = wiredPipeline();

        ImportTemplateDTO template = new ImportTemplateDTO();
        template.setModelName("EmpAddress");
        template.setImportRule(importRule);
        template.setUniqueConstraints(List.of("code"));
        template.setSkipException(true);

        ImportDataDTO data = new ImportDataDTO();
        data.setRows(new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("code", "ADR001", "line1", "first")),
                new LinkedHashMap<>(Map.of("code", "ADR001", "line1", "second")))));
        data.setOriginalRows(new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("Code", "ADR001", "Address Line1", "first")),
                new LinkedHashMap<>(Map.of("Code", "ADR001", "Address Line1", "second")))));

        pipeline.importData(template, data);

        assertThat(data.getRows()).as("only the first row reaches persistence under %s", importRule)
                .hasSize(1);
        assertThat(data.getRows().getFirst()).containsEntry("line1", "first");
        assertThat(data.getFailedRows()).as("and the other comes back with a reason").hasSize(1);
        assertThat(data.getFailedRows().getFirst().get(FileConstant.FAILED_REASON).toString())
                .contains("An earlier row in this file already has code=ADR001");
    }

    @Test
    void aHandlerNamedForAnotherModelIsRefused() {
        // The wizard names the handler; one written for another model must not run on these rows.
        ImportRowPipeline pipeline = wiredPipeline();
        CustomImportHandler employeeOnly = new CustomImportHandler() {
            @Override
            public boolean supports(String modelName) {
                return "Employee".equals(modelName);
            }

            @Override
            public void handleImportData(List<Map<String, Object>> rows, Map<String, Object> env,
                                         boolean validateOnly) {
                rows.forEach(row -> row.put("touched", true));
            }
        };
        ImportDataDTO data = new ImportDataDTO();
        data.setRows(new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("code", "ADR001")))));
        data.setOriginalRows(new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("Code", "ADR001")))));

        try (MockedStatic<SpringContextUtils> spring = mockStatic(SpringContextUtils.class)) {
            spring.when(() -> SpringContextUtils.getBean("employeeOnly", CustomImportHandler.class))
                    .thenReturn(employeeOnly);

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> pipeline.importData(template("EmpAddress", "employeeOnly"), data));
            assertThat(refused.getMessage()).contains("employeeOnly", "EmpAddress");
            assertThat(data.getRows().getFirst()).doesNotContainKey("touched");

            assertDoesNotThrow(() -> pipeline.validateData(template("Employee", "employeeOnly"), data));
            assertThat(data.getRows().getFirst()).containsEntry("touched", true);
        }
    }

    @Test
    void aHandlerThatNamesNoModelServesNone() {
        ImportRowPipeline pipeline = wiredPipeline();
        CustomImportHandler silent = (rows, env, validateOnly) -> { };
        ImportDataDTO data = new ImportDataDTO();
        data.setRows(new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("code", "ADR001")))));
        data.setOriginalRows(new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("Code", "ADR001")))));

        try (MockedStatic<SpringContextUtils> spring = mockStatic(SpringContextUtils.class)) {
            spring.when(() -> SpringContextUtils.getBean("silent", CustomImportHandler.class)).thenReturn(silent);

            assertThrows(IllegalArgumentException.class,
                    () -> pipeline.importData(template("EmpAddress", "silent"), data));
        }
    }

    private static ImportTemplateDTO template(String modelName, String handler) {
        ImportTemplateDTO template = new ImportTemplateDTO();
        template.setModelName(modelName);
        template.setImportRule(ImportRule.CREATE_OR_UPDATE);
        template.setSkipException(true);
        template.setCustomHandler(handler);
        return template;
    }

    @Test
    void aBlankRequiredColumnFailsOnlyTheCreatingRowAndTheNoteNeverReachesTheWrite() {
        ImportRowPipeline pipeline = wiredPipeline();
        ImportTemplateDTO template = new ImportTemplateDTO();
        template.setModelName("Employee");
        template.setImportRule(ImportRule.CREATE_OR_UPDATE);
        template.setUniqueConstraints(List.of("code"));
        template.setSkipException(true);
        template.setIgnoreEmpty(true);

        Map<String, Object> updating = new LinkedHashMap<>(Map.of("code", "E1"));
        RequiredOnCreateResolver.record(updating, "Name");
        Map<String, Object> creating = new LinkedHashMap<>(Map.of("code", "E2"));
        RequiredOnCreateResolver.record(creating, "Name");
        ImportDataDTO data = new ImportDataDTO();
        data.setRows(new ArrayList<>(List.of(updating, creating)));
        data.setOriginalRows(new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("Code", "E1")), new LinkedHashMap<>(Map.of("Code", "E2")))));

        pipeline.importData(template, data);

        assertThat(data.getRows()).hasSize(1);
        assertThat(data.getRows().getFirst()).containsEntry("code", "E1")
                .doesNotContainKey(RequiredOnCreateResolver.MARKER);
        assertThat(data.getFailedRows()).hasSize(1);
        assertThat(data.getFailedRows().getFirst())
                .containsEntry(FileConstant.FAILED_REASON, "The field `Name` is required")
                .doesNotContainKey(RequiredOnCreateResolver.MARKER);
    }

    /**
     * The pipeline with its collaborators stubbed out to no-ops, except the two under test: the
     * in-file duplicate pass and the failure collector that acts on what it marks.
     */
    private static ImportRowPipeline wiredPipeline() {
        ImportRowPipeline pipeline = new ImportRowPipeline();
        ImportHandlerFactory handlerFactory = mock(ImportHandlerFactory.class);
        when(handlerFactory.createHandlers(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        RelationLookupResolver lookupResolver = mock(RelationLookupResolver.class);
        when(lookupResolver.detectLookupGroups(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        ReflectionTestUtils.setField(pipeline, "importHandlerFactory", handlerFactory);
        ReflectionTestUtils.setField(pipeline, "relationLookupResolver", lookupResolver);
        // The database pre-check (ONLY_CREATE only) is not what this exercises; it needs a service,
        // and an empty answer means "nothing already exists" so it stays out of the way.
        UniqueConstraintValidator validator = new UniqueConstraintValidator();
        ModelService<?> modelService = mock(ModelService.class);
        when(modelService.searchList(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        ReflectionTestUtils.setField(validator, "modelService", modelService);
        ReflectionTestUtils.setField(pipeline, "uniqueConstraintValidator", validator);
        RequiredOnCreateResolver requiredResolver = new RequiredOnCreateResolver();
        when(modelService.splitByExistence(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    // Code E1 is stored; every other row creates.
                    List<Map<String, Object>> given = inv.getArgument(1);
                    return new io.softa.framework.orm.domain.CreateOrUpdateResult(
                            given.stream().filter(r -> !"E1".equals(r.get("code"))).toList(),
                            given.stream().filter(r -> "E1".equals(r.get("code"))).toList());
                });
        ReflectionTestUtils.setField(requiredResolver, "modelService", modelService);
        ReflectionTestUtils.setField(pipeline, "requiredOnCreateResolver", requiredResolver);
        ReflectionTestUtils.setField(pipeline, "importFailureCollector", new ImportFailureCollector());
        ReflectionTestUtils.setField(pipeline, "importPersistenceService", mock(ImportPersistenceService.class));
        return pipeline;
    }
}
