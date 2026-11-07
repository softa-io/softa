package io.softa.framework.web.controller;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.web.dto.SearchNameParams;
import io.softa.framework.web.dto.SourceRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A company picker on a record being written offers the companies of the roles holding that write, not
 * the company list as the user reads it — which every role's grants add up to, the self-service role's
 * "every company" included.
 */
class SearchNameWritableCompaniesTest {

    private final PermissionService permissionService = mock(PermissionService.class);

    private static SearchNameParams from(String model, Object recordId) {
        SourceRecord source = new SourceRecord();
        source.setModel(model);
        source.setRecordId(recordId == null ? null : String.valueOf(recordId));
        SearchNameParams params = new SearchNameParams();
        params.setSourceRecord(source);
        return params;
    }

    private Set<Long> writable(String modelName, SearchNameParams params) {
        try (MockedStatic<ModelManager> mm = mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
            return ModelController.writableCompanies(modelName, params, permissionService);
        }
    }

    @Test
    void aNewRecordOffersTheCompaniesItsCreatorsAreGranted() {
        when(permissionService.getGrantedCompanyIds("Employee", AccessType.CREATE)).thenReturn(Set.of(10L));

        assertThat(writable("Company", from("Employee", null))).containsExactly(10L);
    }

    @Test
    void aStoredRecordOffersTheCompaniesItsEditorsAreGranted() {
        when(permissionService.getGrantedCompanyIds("Employee", AccessType.UPDATE, "7")).thenReturn(Set.of(20L));

        assertThat(writable("Company", from("Employee", 7L))).containsExactly(20L);
    }

    @Test
    void aWriteGrantedNoCompanyOffersNone() {
        // Every company the picker could list would be refused by the save.
        when(permissionService.getGrantedCompanyIds("Employee", AccessType.CREATE)).thenReturn(Set.of());

        assertThat(writable("Company", from("Employee", null))).isEmpty();
    }

    @Test
    void anUnrestrictedWriteLeavesThePickerAsItReads() {
        when(permissionService.getGrantedCompanyIds("Employee", AccessType.UPDATE, "7")).thenReturn(null);

        assertThat(writable("Company", from("Employee", 7L))).isNull();
    }

    @Test
    void aSearchForNoRecordIsLeftAsItReads() {
        // A list filter or a report picks a company to read by, not to write.
        assertThat(writable("Company", new SearchNameParams())).isNull();
        assertThat(writable("Company", null)).isNull();
        verifyNoInteractions(permissionService);
    }

    @Test
    void onlyTheCompanyModelIsNarrowed() {
        assertThat(writable("Department", from("Employee", null))).isNull();
        verifyNoInteractions(permissionService);
    }

    @Test
    void anUnknownSourceModelIsIgnored() {
        assertThat(writable("Company", from("NoSuchModel", null))).isNull();
        verifyNoInteractions(permissionService);
    }
}
