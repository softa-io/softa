package io.softa.starter.file.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.service.ImportTemplateService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which imports a page shows.
 *
 * <p>The template list a page offers is the model's own plus its CHILD models' — that is how one
 * employee page hands out the templates for addresses, family members and the rest. The history read
 * the model alone, so an import started from that page finished and then appeared nowhere on it: the
 * file uploaded, the rows landed, the list stayed empty, and the only way to see the run was a SQL
 * client.
 */
class ImportHistoryServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void theHistoryCoversTheSameModelsTheTemplateListOffers() {
        try (var models = org.mockito.Mockito.mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.getChildModels("Employee"))
                    .thenReturn(Set.of("EmpAddress", "EmpFamilyMember"));
            models.when(() -> ModelManager.getModel(anyString())).thenReturn(new MetaModel());

            ModelService<Long> modelService = mock(ModelService.class);
            when(modelService.searchList(anyString(), any(FlexQuery.class))).thenReturn(List.of());

            ImportHistoryServiceImpl service = serviceWith(modelService, List.of());

            Context context = new Context();
            context.setUserId(7L);
            ContextHolder.runWith(context, () -> service.listMyImportHistory("Employee"));

            ArgumentCaptor<FlexQuery> query = ArgumentCaptor.forClass(FlexQuery.class);
            verify(modelService).searchList(anyString(), query.capture());

            String filters = String.valueOf(query.getValue().getFilters());
            assertThat(filters)
                    .as("a child model's import must be visible where its template was offered")
                    .contains("EmpAddress")
                    .contains("EmpFamilyMember")
                    .contains("Employee");
        }
    }

    /**
     * And it drops the same models the template list drops.
     *
     * <p>Department is a child of Employee only because Employee declares `managedDepartments` /
     * `hrbpDepartments` — a reverse reference, not a composition. Its template is marked standalone so
     * it stays off the employee page (zingkey/zingkey-hcm#764); the history has to agree, or the page
     * lists runs for a template it does not offer.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theHistoryDropsTheSameModelsTheTemplateListDrops() {
        try (var models = org.mockito.Mockito.mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.getChildModels("Employee"))
                    .thenReturn(Set.of("EmpAddress", "Department"));
            models.when(() -> ModelManager.getModel(anyString())).thenReturn(new MetaModel());

            ModelService<Long> modelService = mock(ModelService.class);
            when(modelService.searchList(anyString(), any(FlexQuery.class))).thenReturn(List.of());

            ImportTemplate standalone = new ImportTemplate();
            standalone.setModelName("Department");
            standalone.setStandalone(true);
            ImportHistoryServiceImpl service = serviceWith(modelService, List.of(standalone));

            Context context = new Context();
            context.setUserId(7L);
            ContextHolder.runWith(context, () -> service.listMyImportHistory("Employee"));

            ArgumentCaptor<FlexQuery> query = ArgumentCaptor.forClass(FlexQuery.class);
            verify(modelService).searchList(anyString(), query.capture());

            String filters = String.valueOf(query.getValue().getFilters());
            assertThat(filters)
                    .as("the employee page does not offer the Department template, so it must not "
                            + "list Department imports either")
                    .doesNotContain("Department")
                    .contains("EmpAddress")
                    .contains("Employee");
        }
    }

    /** Wiring shared by both cases; the template service answers which models are standalone. */
    @SuppressWarnings("unchecked")
    private static ImportHistoryServiceImpl serviceWith(ModelService<Long> modelService,
                                                        List<ImportTemplate> standaloneTemplates) {
        ImportTemplateService templateService = mock(ImportTemplateService.class);
        when(templateService.searchList(any(FlexQuery.class))).thenReturn(standaloneTemplates);

        ImportHistoryServiceImpl service = new ImportHistoryServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelService", modelService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelName", "ImportHistory");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "importTemplateService", templateService);
        return service;
    }
}
