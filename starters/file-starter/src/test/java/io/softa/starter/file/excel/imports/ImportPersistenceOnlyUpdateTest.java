package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.domain.CreateOrUpdateResult;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportDataDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.enums.ImportRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ONLY_UPDATE rule — a row that matches nothing is a failed row, not a new record.
 *
 * <p>The rule ran the same call as CREATE_OR_UPDATE, so "only update" inserted. A file of
 * corrections with one mistyped key produced an extra record instead of an error, and the mistake
 * surfaced later as a duplicate nobody could account for.
 */
class ImportPersistenceOnlyUpdateTest {

    private static final String MODEL = "Employee";
    private static final List<String> KEYS = List.of("code");

    private static ImportPersistenceService serviceWith(ModelService<?> modelService) {
        ImportPersistenceService service = new ImportPersistenceService();
        ReflectionTestUtils.setField(service, "modelService", modelService);
        return service;
    }

    private static ImportTemplateDTO template(ImportRule rule, boolean skipException) {
        ImportTemplateDTO template = new ImportTemplateDTO();
        template.setModelName(MODEL);
        template.setImportRule(rule);
        template.setUniqueConstraints(KEYS);
        template.setSkipException(skipException);
        return template;
    }

    private static Map<String, Object> row(String code) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("code", code);
        return row;
    }

    private static ImportDataDTO data(List<Map<String, Object>> rows, List<Map<String, Object>> originals) {
        ImportDataDTO dto = new ImportDataDTO();
        dto.setRows(new ArrayList<>(rows));
        dto.setOriginalRows(new ArrayList<>(originals));
        return dto;
    }

    @Test
    void aRowThatMatchesNothingIsRefusedRatherThanInserted() {
        ModelService<?> modelService = mock(ModelService.class);
        Map<String, Object> unmatched = row("NOPE");
        when(modelService.splitByExistence(eq(MODEL), anyList(), eq(KEYS)))
                .thenReturn(new CreateOrUpdateResult(List.of(unmatched), List.of()));

        ImportDataDTO dto = data(List.of(unmatched), List.of(row("NOPE")));

        assertThatThrownBy(() -> serviceWith(modelService).persist(template(ImportRule.ONLY_UPDATE, false), dto))
                .isInstanceOf(BusinessException.class);

        verify(modelService, never()).createList(any(), anyList());
        verify(modelService, never()).createOrUpdate(any(), anyList(), anyList());
    }

    @Test
    void aMatchedRowIsUpdated() {
        ModelService<?> modelService = mock(ModelService.class);
        Map<String, Object> matched = row("E1");
        when(modelService.splitByExistence(eq(MODEL), anyList(), eq(KEYS)))
                .thenReturn(new CreateOrUpdateResult(List.of(), List.of(matched)));

        serviceWith(modelService).persist(template(ImportRule.ONLY_UPDATE, false),
                data(List.of(matched), List.of(row("E1"))));

        verify(modelService).updateList(eq(MODEL), eq(List.of(matched)));
        verify(modelService, never()).createList(any(), anyList());
    }

    @Test
    void theUnmatchedRowIsTheOnlyOneThatFails() {
        // skipException falls back to row-by-row, which is what turns the refusal into a reported
        // failure instead of losing the whole file: the matched rows still land.
        ModelService<?> modelService = mock(ModelService.class);
        Map<String, Object> good = row("E1");
        Map<String, Object> bad = row("NOPE");
        when(modelService.splitByExistence(eq(MODEL), anyList(), eq(KEYS)))
                .thenAnswer(call -> {
                    List<Map<String, Object>> batch = call.getArgument(1);
                    boolean hasBad = batch.stream().anyMatch(r -> "NOPE".equals(r.get("code")));
                    return hasBad && batch.size() == 1
                            ? new CreateOrUpdateResult(List.of(bad), List.of())
                            : hasBad
                                    ? new CreateOrUpdateResult(List.of(bad), List.of(good))
                                    : new CreateOrUpdateResult(List.of(), batch);
                });

        ImportDataDTO dto = data(List.of(good, bad), List.of(row("E1"), row("NOPE")));
        serviceWith(modelService).persist(template(ImportRule.ONLY_UPDATE, true), dto);

        assertThat(dto.getFailedRows()).hasSize(1);
        assertThat(dto.getFailedRows().getFirst()).containsEntry("code", "NOPE");
        assertThat(dto.getFailedRows().getFirst().get(FileConstant.FAILED_REASON))
                .asString().contains("only updates");
        assertThat(dto.getRows()).containsExactly(good);
    }
}
