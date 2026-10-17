package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.user.dto.UserListViewPreferenceDTO;
import io.softa.starter.user.entity.UserListViewPreference;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * An arrangement holds field names, never authority: whatever the reader may not read now is dropped
 * when it is served and when it is saved — a withdrawn sensitive set takes its columns with it.
 */
class UserListViewPreferenceServiceImplTest {

    private static final String EMPLOYEE = "Employee";
    private static final String CONTACT = "EmpEmergencyContact";
    private static final String PROFILE = "EmployeeProfile";

    private MockedStatic<ModelManager> modelManager;
    private UserListViewPreferenceServiceImpl service;

    @BeforeEach
    void setUp() {
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel(any())).thenReturn(true);
        modelManager.when(() -> ModelManager.existField(EMPLOYEE, "emergencyContacts")).thenReturn(true);
        MetaField contacts = mock(MetaField.class);
        when(contacts.getRelatedModel()).thenReturn(CONTACT);
        modelManager.when(() -> ModelManager.getModelField(EMPLOYEE, "emergencyContacts")).thenReturn(contacts);

        // Employee → EmployeeProfile is one-to-one; Employee → Department is many-to-one.
        field(EMPLOYEE, "employeeProfileId", FieldType.ONE_TO_ONE, PROFILE, null);
        field(EMPLOYEE, "departmentId", FieldType.MANY_TO_ONE, "Department", null);
        field(EMPLOYEE, "ipaPosition", FieldType.STRING, null, List.of("SG"));
        field(PROFILE, "passNumber", FieldType.STRING, null, null);
        field(PROFILE, "idNumber", FieldType.STRING, null, null);
        field(PROFILE, "kiwiSaverStatus", FieldType.STRING, null, List.of("NZ"));

        PermissionService permissions = mock(PermissionService.class);
        // The reader's sensitive sets withhold Employee.salary, the contact's phone number and the
        // profile's ID number.
        when(permissions.filterReadableFields(eq(EMPLOYEE), anyCollection(), eq(AccessType.READ)))
                .thenAnswer(call -> without(call.getArgument(1), Set.of("salary")));
        when(permissions.filterReadableFields(eq(CONTACT), anyCollection(), eq(AccessType.READ)))
                .thenAnswer(call -> without(call.getArgument(1), Set.of("phoneNumber")));
        when(permissions.filterReadableFields(eq(PROFILE), anyCollection(), eq(AccessType.READ)))
                .thenAnswer(call -> without(call.getArgument(1), Set.of("idNumber")));

        service = new UserListViewPreferenceServiceImpl();
        ReflectionTestUtils.setField(service, "permissionService", permissions);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void dropsTheFieldsTheReaderMayNotReadAndKeepsTheOrderOfTheRest() {
        UserListViewPreferenceDTO dto = arrangement();
        dto.setColumnOrder(List.of("fullName", "salary", "code"));
        dto.setHiddenColumns(List.of("salary"));
        dto.setExportFields(List.of("salary", "fullName", "code"));

        UserListViewPreferenceDTO out = service.readable(dto);

        assertThat(out.getColumnOrder()).containsExactly("fullName", "code");
        assertThat(out.getHiddenColumns()).isEmpty();
        assertThat(out.getExportFields()).containsExactly("fullName", "code");
    }

    @Test
    void checksAnObjectsFieldsAgainstTheModelThatObjectIs() {
        UserListViewPreferenceDTO dto = arrangement();
        Map<String, List<String>> objects = new LinkedHashMap<>();
        objects.put("emergencyContacts", List.of("name", "phoneNumber"));
        dto.setExportObjectFields(objects);

        UserListViewPreferenceDTO out = service.readable(dto);

        assertThat(out.getExportObjectFields()).containsOnlyKeys("emergencyContacts");
        assertThat(out.getExportObjectFields().get("emergencyContacts")).containsExactly("name");
    }

    @Test
    void dropsAnObjectTheListsModelNoLongerHas() {
        UserListViewPreferenceDTO dto = arrangement();
        dto.setExportObjectFields(Map.of("retiredCollection", List.of("name")));

        assertThat(service.readable(dto).getExportObjectFields()).isEmpty();
    }

    @Test
    void leavesPartsThatWereNotSentAsNull() {
        // Null means "not part of this save", so the export dialog's save cannot wipe the columns.
        UserListViewPreferenceDTO out = service.readable(arrangement());

        assertThat(out.getColumnOrder()).isNull();
        assertThat(out.getExportFields()).isNull();
        assertThat(out.getExportObjectFields()).isNull();
    }

    @Test
    void refusesAnArrangementNoListWouldProduce() {
        UserListViewPreferenceDTO dto = arrangement();
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= UserListViewPreferenceServiceImpl.MAX_NAMES; i++) tooMany.add("f" + i);
        dto.setExportFields(tooMany);

        assertThatThrownBy(() -> service.saveMine(dto)).isInstanceOf(BusinessException.class);
    }

    @Test
    void mergesViewSettingsKeyByKeyAndRemovesAKeySentAsNull() {
        // Saving the tree's state must not wipe a setting saved by another part of the list.
        String stored = "{\"sidePanelCollapsed\":false,\"other\":1}";
        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("sidePanelCollapsed", true);
        assertThat(UserListViewPreferenceServiceImpl.mergeSettings(stored, incoming))
                .isEqualTo("{\"sidePanelCollapsed\":true,\"other\":1}");

        Map<String, Object> removal = new LinkedHashMap<>();
        removal.put("sidePanelCollapsed", null);
        removal.put("other", null);
        assertThat(UserListViewPreferenceServiceImpl.mergeSettings(stored, removal)).isNull();
    }

    @Test
    void refusesViewSettingsTooLargeToBeAFewFlags() {
        Map<String, Object> incoming = Map.of("blob", "x".repeat(UserListViewPreferenceServiceImpl.MAX_SETTINGS_LENGTH));
        assertThatThrownBy(() -> UserListViewPreferenceServiceImpl.mergeSettings(null, incoming))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void anAddedColumnOneLevelIntoAOneToOneRecordIsCheckedAgainstThatRecordsModel() {
        // The list's model knows nothing of the profile's sensitive sets: checked against Employee
        // alone, employeeProfileId.idNumber would pass because no Employee field of that name exists.
        UserListViewPreferenceDTO dto = arrangement();
        dto.setAddedColumns(List.of("employeeProfileId.passNumber", "employeeProfileId.idNumber"));
        dto.setColumnOrder(List.of("fullName", "employeeProfileId.passNumber", "employeeProfileId.idNumber"));

        UserListViewPreferenceDTO out = service.readable(dto);

        assertThat(out.getAddedColumns()).containsExactly("employeeProfileId.passNumber");
        assertThat(out.getColumnOrder()).containsExactly("fullName", "employeeProfileId.passNumber");
    }

    @Test
    void anExportFieldOnAOneToOneRecordIsCheckedAgainstThatRecordsModel() {
        UserListViewPreferenceDTO dto = arrangement();
        dto.setExportFields(List.of("fullName", "employeeProfileId.passNumber", "employeeProfileId.idNumber"));

        UserListViewPreferenceDTO out = service.readable(dto);

        assertThat(out.getExportFields()).containsExactly("fullName", "employeeProfileId.passNumber");
    }

    @Test
    void aPathThroughAnythingButOneOneToOneLinkNamesNoColumn() {
        UserListViewPreferenceDTO dto = arrangement();
        dto.setAddedColumns(List.of(
                "departmentId.name",                          // many-to-one: not this feature's
                "employeeProfileId.passNumber.length",        // two levels
                "employeeProfileId.retired",                  // a field the record no longer has
                "retiredField"));                             // a field the list no longer has

        assertThat(service.readable(dto).getAddedColumns()).isEmpty();
    }

    @Test
    void aColumnOnlyOtherCountriesUseIsNotTheCallers() {
        UserListViewPreferenceDTO dto = arrangement();
        dto.setAddedColumns(List.of("ipaPosition", "employeeProfileId.kiwiSaverStatus"));

        assertThat(asCallerIn(Set.of("NZ"), () -> service.readable(copy(dto)).getAddedColumns()))
                .containsExactly("employeeProfileId.kiwiSaverStatus");
        assertThat(asCallerIn(Set.of("SG", "NZ"), () -> service.readable(copy(dto)).getAddedColumns()))
                .containsExactly("ipaPosition", "employeeProfileId.kiwiSaverStatus");
        // Countries unknown: not narrowed, the same reading the value lists make.
        assertThat(asCallerIn(null, () -> service.readable(copy(dto)).getAddedColumns()))
                .containsExactly("ipaPosition", "employeeProfileId.kiwiSaverStatus");
    }

    @Test
    void anUnseenColumnGoesBackAfterTheColumnItFollowed() {
        List<String> stored = List.of("fullName", "code", "kiwi", "ipa");
        List<String> incoming = List.of("fullName", "code", "ipa", "idExpiry");

        assertThat(UserListViewPreferenceServiceImpl.reinserted(stored, incoming, Set.of("kiwi")::contains))
                .containsExactly("fullName", "code", "kiwi", "ipa", "idExpiry");
    }

    @Test
    void aRunOfUnseenColumnsKeepsItsOrderAndALeadingOneGoesFirst() {
        List<String> stored = List.of("x", "a", "y", "z", "b");
        List<String> incoming = List.of("b", "a");

        assertThat(UserListViewPreferenceServiceImpl.reinserted(stored, incoming, Set.of("x", "y", "z")::contains))
                .containsExactly("x", "b", "a", "y", "z");
    }

    @Test
    void aColumnTheCallerCouldSeeAndLeftOutIsRemovedNotKept() {
        // Absent from what was sent means removed, unless the caller could not have seen it.
        List<String> stored = List.of("fullName", "code", "hireDate");
        List<String> incoming = List.of("fullName", "code");

        assertThat(UserListViewPreferenceServiceImpl.reinserted(stored, incoming, id -> false))
                .containsExactly("fullName", "code");
    }

    @Test
    void keepsUnseenEntriesOfEveryColumnListButOnlyForThePartsBeingSaved() {
        UserListViewPreferenceDTO stored = arrangement();
        stored.setColumnOrder(List.of("fullName", "kiwi"));
        stored.setHiddenColumns(List.of("kiwi"));
        stored.setAddedColumns(List.of("kiwi"));
        UserListViewPreferenceDTO incoming = arrangement();
        incoming.setColumnOrder(List.of("fullName", "passNo"));
        incoming.setAddedColumns(List.of("passNo"));

        UserListViewPreferenceServiceImpl.keepUnseen(incoming, stored, Set.of("kiwi")::contains);

        assertThat(incoming.getColumnOrder()).containsExactly("fullName", "kiwi", "passNo");
        assertThat(incoming.getAddedColumns()).containsExactly("passNo", "kiwi");
        // Not sent, not touched: the stored hidden list stays as it is.
        assertThat(incoming.getHiddenColumns()).isNull();
    }

    @Test
    void stateOfTellsAnUnseenColumnFromAGoneOne() {
        assertThat(service.stateOf(EMPLOYEE, "employeeProfileId.idNumber"))
                .isEqualTo(UserListViewPreferenceServiceImpl.PathState.UNSEEN);
        assertThat(service.stateOf(EMPLOYEE, "employeeProfileId.retired"))
                .isEqualTo(UserListViewPreferenceServiceImpl.PathState.GONE);
        assertThat(service.stateOf(EMPLOYEE, "employeeProfileId.passNumber"))
                .isEqualTo(UserListViewPreferenceServiceImpl.PathState.VISIBLE);
    }

    private static UserListViewPreferenceDTO arrangement() {
        UserListViewPreferenceDTO dto = new UserListViewPreferenceDTO();
        dto.setViewStateKey("table|Employee|/corehr/employee/employee|");
        dto.setModelName(EMPLOYEE);
        return dto;
    }

    private void field(String model, String name, FieldType type, String related, List<String> countries) {
        MetaField field = mock(MetaField.class);
        when(field.getFieldType()).thenReturn(type);
        when(field.getRelatedModel()).thenReturn(related);
        when(field.getCountries()).thenReturn(countries);
        modelManager.when(() -> ModelManager.getModelFieldOrNull(model, name)).thenReturn(field);
    }

    private static <T> T asCallerIn(Set<String> countries, java.util.function.Supplier<T> action) {
        Context ctx = new Context();
        ctx.setGrantedCountries(countries);
        return ContextHolder.callWith(ctx, action::get);
    }

    private static UserListViewPreferenceDTO copy(UserListViewPreferenceDTO dto) {
        UserListViewPreferenceDTO out = arrangement();
        out.setAddedColumns(dto.getAddedColumns());
        return out;
    }

    private static List<String> without(Collection<String> names, Set<String> blocked) {
        return names.stream().filter(n -> !blocked.contains(n)).toList();
    }

    @Test
    void aSaveWritesOnlyThePartsItCarries() {
        // The side tree and the columns of one page save at once: writing back what this request read
        // of the other part would undo that other save.
        UserListViewPreference stored = new UserListViewPreference();
        stored.setId(7L);
        stored.setColumnOrder("[\"code\"]");
        stored.setViewSettings("{\"sidePanelCollapsed\":true,\"density\":\"compact\"}");
        UserListViewPreferenceDTO incoming = new UserListViewPreferenceDTO();
        incoming.setModelName(EMPLOYEE);
        incoming.setViewSettings(new LinkedHashMap<>(Map.of("sidePanelCollapsed", false)));

        Map<String, Object> row = UserListViewPreferenceServiceImpl.changes(stored, incoming);

        assertThat(row).containsOnlyKeys("id", "modelName", "viewSettings");
        assertThat((String) row.get("viewSettings")).contains("\"sidePanelCollapsed\":false").contains("\"density\":\"compact\"");
    }

    @Test
    void anExportFieldTheCallerCannotSeeNowKeepsItsPlace() {
        UserListViewPreferenceDTO stored = new UserListViewPreferenceDTO();
        stored.setExportFields(List.of("fullName", "salary", "code"));
        UserListViewPreferenceDTO incoming = new UserListViewPreferenceDTO();
        incoming.setExportFields(List.of("fullName", "code", "hireDate"));

        UserListViewPreferenceServiceImpl.keepUnseen(incoming, stored, "salary"::equals);

        assertThat(incoming.getExportFields()).containsExactly("fullName", "salary", "code", "hireDate");
    }

    @Test
    void aDamagedPartReadsAsNotArrangedRatherThanFailingTheList() {
        UserListViewPreference row = new UserListViewPreference();
        row.setViewStateKey("table|Employee|/e|");
        row.setModelName(EMPLOYEE);
        row.setColumnOrder("[\"code\"");
        row.setHiddenColumns("[\"remark\"]");
        row.setViewSettings("{not json");

        UserListViewPreferenceDTO dto = UserListViewPreferenceServiceImpl.toDto(row);

        assertThat(dto.getColumnOrder()).isNull();
        assertThat(dto.getViewSettings()).isNull();
        assertThat(dto.getHiddenColumns()).containsExactly("remark");
    }
}
