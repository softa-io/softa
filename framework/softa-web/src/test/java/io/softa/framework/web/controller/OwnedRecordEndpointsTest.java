package io.softa.framework.web.controller;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.web.dto.QueryParams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The records a record owns are read through it: the owner has to be readable, a row has to be the owner's, and
 * only a one-to-one or keyed one-to-many field opens them.
 */
class OwnedRecordEndpointsTest {

    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final ModelController<Serializable> controller = new ModelController<>();
    private MockedStatic<ModelManager> models;
    private MockedStatic<IdUtils> ids;

    private static MetaField field(FieldType type, String related, String relatedField) {
        MetaField field = mock(MetaField.class);
        when(field.getFieldType()).thenReturn(type);
        when(field.getRelatedModel()).thenReturn(related);
        when(field.getRelatedField()).thenReturn(relatedField);
        return field;
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "modelService", modelService);
        MetaField addresses = field(FieldType.ONE_TO_MANY, "EmpAddress", "employeeId");
        MetaField bank = field(FieldType.ONE_TO_ONE, "EmpBankAccount", null);
        MetaField department = field(FieldType.MANY_TO_ONE, "Department", null);
        models = mockStatic(ModelManager.class);
        models.when(() -> ModelManager.getModelField("Employee", "empAddresses")).thenReturn(addresses);
        models.when(() -> ModelManager.getModelField("Employee", "empBankAccountId")).thenReturn(bank);
        models.when(() -> ModelManager.getModelField("Employee", "departmentId")).thenReturn(department);
        ids = mockStatic(IdUtils.class);
        ids.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterEach
    void tearDown() {
        ids.close();
        models.close();
    }

    private void ownerReadable(boolean readable) {
        when(modelService.getById(eq("Employee"), eq(7L), eq(List.of(ModelConstant.ID))))
                .thenReturn(readable ? Optional.of(Map.of(ModelConstant.ID, 7L)) : Optional.empty());
    }

    @Test
    void aPageOfOwnedRowsIsLimitedToTheOwnersRows() {
        ownerReadable(true);
        FlexQuery[] seen = new FlexQuery[1];
        when(modelService.searchPage(eq("EmpAddress"), any(FlexQuery.class), any())).thenAnswer(invocation -> {
            seen[0] = invocation.getArgument(1);
            return Page.of(1, 10);
        });

        controller.searchRelationPage("Employee", 7L, "empAddresses", new QueryParams());

        assertThat(String.valueOf(seen[0].getFilters())).contains("employeeId").contains("7");
    }

    @Test
    void anOwnerTheCallerCannotReadOpensNothing() {
        ownerReadable(false);

        assertThatThrownBy(() -> controller.searchRelationPage("Employee", 7L, "empAddresses", new QueryParams()))
                .hasMessageContaining("does not exist");
        verify(modelService, never()).searchPage(anyString(), any(FlexQuery.class), any());
    }

    @Test
    void onlyAFieldThroughWhichTheModelOwnsRecordsOpensThem() {
        assertThatThrownBy(() -> controller.searchRelationPage("Employee", 7L, "departmentId", new QueryParams()))
                .hasMessageContaining("not a one-to-one or one-to-many field");
        assertThatThrownBy(() -> controller.searchRelationPage("Employee", 7L, "empBankAccountId", new QueryParams()))
                .hasMessageContaining("not a one-to-many field");
    }

    @Test
    void aSectionsMaskedFieldIsReadThroughTheOwnersLink() {
        when(modelService.getById(eq("Employee"), eq(7L), eq(List.of("empBankAccountId"))))
                .thenReturn(Optional.of(Map.of("empBankAccountId", 42L)));
        when(modelService.getUnmaskedFields("EmpBankAccount", 42L, List.of("accountNumber")))
                .thenReturn(Map.of("accountNumber", "123"));

        Map<String, Object> values = controller.getRelationUnmaskedFields("Employee", 7L, "empBankAccountId", null,
                List.of("accountNumber"), null).getData();

        assertThat(values).containsEntry("accountNumber", "123");
    }

    @Test
    void aRowOfAnotherOwnerIsRefused() {
        ownerReadable(true);
        when(modelService.count(eq("EmpAddress"), any(Filters.class))).thenReturn(0L);

        assertThatThrownBy(() -> controller.getRelationUnmaskedFields("Employee", 7L, "empAddresses", "99",
                List.of("line1"), null))
                .hasMessageContaining("does not belong");
        verify(modelService, never()).getUnmaskedFields(anyString(), any(Serializable.class), anyList());
    }
}
