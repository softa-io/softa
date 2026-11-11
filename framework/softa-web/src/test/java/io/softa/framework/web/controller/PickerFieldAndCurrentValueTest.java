package io.softa.framework.web.controller;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.web.dto.SearchNameParams;
import io.softa.framework.web.dto.SourceRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A company picker is narrowed only for a field the save judges, and an edited record's current value stays among a
 * narrowed picker's options.
 */
class PickerFieldAndCurrentValueTest {

    private final PermissionService permissionService = mock(PermissionService.class);
    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);

    private static SourceRecord source(Object recordId, String fieldName) {
        SourceRecord source = new SourceRecord();
        source.setModel("Employee");
        source.setRecordId(recordId == null ? null : String.valueOf(recordId));
        source.setFieldName(fieldName);
        return source;
    }

    private static MetaField companyField(boolean dynamic, String cascadedField) {
        MetaField field = mock(MetaField.class);
        when(field.getFieldType()).thenReturn(FieldType.MANY_TO_ONE);
        when(field.getRelatedModel()).thenReturn(ModelConstant.COMPANY_MODEL);
        when(field.isDynamic()).thenReturn(dynamic);
        when(field.getCascadedField()).thenReturn(cascadedField);
        return field;
    }

    private static MockedStatic<ModelManager> employeeWith(String fieldName, MetaField field) {
        MockedStatic<ModelManager> mm = mockStatic(ModelManager.class);
        mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
        mm.when(() -> ModelManager.isProjectionModel("Employee")).thenReturn(false);
        mm.when(() -> ModelManager.getModelFieldOrNull("Employee", fieldName)).thenReturn(field);
        return mm;
    }

    @Test
    void aPickerForAStoredCompanyReferenceIsNarrowed() {
        when(permissionService.getGrantedCompanyIds("Employee", AccessType.CREATE)).thenReturn(Set.of(10L));

        try (MockedStatic<ModelManager> ignored = employeeWith("legalEntityId", companyField(false, null))) {
            assertThat(ModelController.writableCompanies("Company", source(null, "legalEntityId"), permissionService))
                    .containsExactly(10L);
        }
    }

    @Test
    void aPickerForAFieldTheSaveDoesNotJudgeReadsAsItReads() {
        // Not stored: it only filters another picker, so nothing bounds what it names.
        try (MockedStatic<ModelManager> ignored = employeeWith("filterCompanyId", companyField(true, null))) {
            assertThat(ModelController.writableCompanies("Company", source(null, "filterCompanyId"), permissionService))
                    .isNull();
        }
        try (MockedStatic<ModelManager> ignored = employeeWith("deptCompanyId", companyField(false, "departmentId.companyId"))) {
            assertThat(ModelController.writableCompanies("Company", source(null, "deptCompanyId"), permissionService))
                    .isNull();
        }
        try (MockedStatic<ModelManager> ignored = employeeWith("nickname", null)) {
            assertThat(ModelController.writableCompanies("Company", source(null, "nickname"), permissionService))
                    .isNull();
        }
        verifyNoInteractions(permissionService);
    }

    @Test
    void aPickerNamingNoFieldIsNarrowedAsBefore() {
        when(permissionService.getGrantedCompanyIds("Employee", AccessType.CREATE)).thenReturn(Set.of(10L));

        try (MockedStatic<ModelManager> ignored = employeeWith("legalEntityId", companyField(false, null))) {
            assertThat(ModelController.writableCompanies("Company", source(null, null), permissionService))
                    .containsExactly(10L);
        }
    }

    @Test
    void anEditedRecordKeepsTheCompanyItNamesNowAmongTheOptions() {
        ModelController<Serializable> controller = controller();
        when(permissionService.getGrantedCompanyIds("Employee", AccessType.UPDATE, "7")).thenReturn(Set.of(10L));
        when(modelService.getById(eq("Employee"), any(), eq(List.of("legalEntityId"))))
                .thenReturn(Optional.of(Map.of("legalEntityId", Map.of("id", 30L, "displayName", "Other Co"))));

        String filters = searchNameFilters(controller, source(7L, "legalEntityId"));

        assertThat(filters).contains("10").contains("30").contains("OR");
    }

    @Test
    void aNewRecordHasNoCurrentValueToKeep() {
        ModelController<Serializable> controller = controller();
        when(permissionService.getGrantedCompanyIds("Employee", AccessType.CREATE)).thenReturn(Set.of(10L));

        String filters = searchNameFilters(controller, source(null, "legalEntityId"));

        assertThat(filters).contains("10").doesNotContain("OR");
        verify(modelService, never()).getById(anyString(), any(), any(List.class));
    }

    @Test
    void aRecordTheCallerCannotReadKeepsNothing() {
        ModelController<Serializable> controller = controller();
        when(permissionService.getGrantedCompanyIds("Employee", AccessType.UPDATE, "7")).thenReturn(Set.of(10L));
        when(modelService.getById(eq("Employee"), any(), eq(List.of("legalEntityId")))).thenReturn(Optional.empty());

        assertThat(searchNameFilters(controller, source(7L, "legalEntityId"))).doesNotContain("OR");
    }

    private ModelController<Serializable> controller() {
        ModelController<Serializable> controller = new ModelController<>();
        ReflectionTestUtils.setField(controller, "modelService", modelService);
        ReflectionTestUtils.setField(controller, "permissionService", permissionService);
        return controller;
    }

    /** The filters the company search runs with, as text. */
    private String searchNameFilters(ModelController<Serializable> controller, SourceRecord source) {
        SearchNameParams params = new SearchNameParams();
        params.setSourceRecord(source);
        FlexQuery[] seen = new FlexQuery[1];
        when(modelService.searchName(eq("Company"), any(FlexQuery.class), any())).thenAnswer(invocation -> {
            seen[0] = invocation.getArgument(1);
            return List.of();
        });
        try (MockedStatic<ModelManager> mm = employeeWith("legalEntityId", companyField(false, null))) {
            mm.when(() -> ModelManager.existModel("Company")).thenReturn(true);
            controller.searchName("Company", params);
        }
        return String.valueOf(seen[0].getFilters());
    }
}
