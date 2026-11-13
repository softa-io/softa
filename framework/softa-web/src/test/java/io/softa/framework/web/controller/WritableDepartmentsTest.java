package io.softa.framework.web.controller;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.web.dto.SearchListParams;
import io.softa.framework.web.dto.SourceRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A department picker on a record being written offers what that write may choose, not the department list as
 * the user reads it, which an exemption from the department grant opens in full.
 */
class WritableDepartmentsTest {

    private static final Filters WITHIN = Filters.of("idPath", Operator.EQUAL, "1/10");

    private final PermissionService permissionService = mock(PermissionService.class);

    private static SourceRecord from(String model, Object recordId) {
        SourceRecord source = new SourceRecord();
        source.setModel(model);
        source.setRecordId(recordId == null ? null : String.valueOf(recordId));
        return source;
    }

    private Filters writable(String modelName, SourceRecord source) {
        try (MockedStatic<ModelManager> mm = mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            return ModelController.writableDepartments(modelName, source, permissionService);
        }
    }

    @Test
    void aNewRecordAsksTheCreateAndAStoredOneTheUpdate() {
        when(permissionService.getWritableDepartmentScope("Employee", AccessType.CREATE)).thenReturn(WITHIN);
        Filters onUpdate = Filters.of("idPath", Operator.EQUAL, "1/20");
        // A stored record asks by its id, so only the writers reaching it answer, as its save is judged.
        when(permissionService.getWritableDepartmentScope("Employee", AccessType.UPDATE, "7")).thenReturn(onUpdate);

        assertThat(writable("Department", from("Employee", null))).isSameAs(WITHIN);
        assertThat(writable("Department", from("Employee", 7L))).isSameAs(onUpdate);
    }

    @Test
    void aFieldThatDoesNotPlaceTheRecordIsNotNarrowed() {
        SourceRecord source = from("Employee", 7L);
        source.setFieldName("newDeptId");

        assertThat(writable("Department", source)).isNull();
        verifyNoInteractions(permissionService);
    }

    @Test
    void theFieldThatPlacesTheRecordIsNarrowed() {
        when(permissionService.getWritableDepartmentScope("Employee", AccessType.UPDATE, "7")).thenReturn(WITHIN);
        SourceRecord source = from("Employee", 7L);
        source.setFieldName("departmentId");

        assertThat(writable("Department", source)).isSameAs(WITHIN);
    }

    @Test
    void onlyTheDepartmentModelIsNarrowed() {
        assertThat(writable("Company", from("Employee", 7L))).isNull();
        verifyNoInteractions(permissionService);
    }

    @Test
    void aReadForNoRecordIsLeftAsItReads() {
        // A list filter or a report picks a department to read by, not to write.
        assertThat(writable("Department", null)).isNull();
        assertThat(writable("Department", from("NoSuchModel", 7L))).isNull();
        assertThat(writable("Department", from(" ", 7L))).isNull();
        verifyNoInteractions(permissionService);
    }

    @Test
    void theTreePickersListReadCarriesTheRecord() {
        // What the tree widget sends; a list body without it still parses as it did.
        SearchListParams params = new SearchListParams();
        params.setSourceRecord(from("Employee", 7L));

        assertThat(params.getSourceRecord().getModel()).isEqualTo("Employee");
        assertThat(new SearchListParams().getSourceRecord()).isNull();
    }
}
