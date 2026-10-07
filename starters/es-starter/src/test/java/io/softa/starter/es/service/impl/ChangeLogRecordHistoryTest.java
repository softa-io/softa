package io.softa.starter.es.service.impl;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One record's history, across the models it is kept in.
 *
 * <p>An employee's personal details, bank account and family members are each logged against
 * their own rows, so the employee row's history alone is most of the record missing — and a
 * deleted family member had no id left to ask about at all. One query now gathers the record's
 * rows: a one-to-one by the row it points at, a one-to-many by the reference each log carries and
 * by the rows that exist now.
 */
class ChangeLogRecordHistoryTest {

    private static final Long EMPLOYEE = 100L;

    private ChangeLogServiceImpl service;
    private PermissionService permissionService;
    @SuppressWarnings("rawtypes")
    private ModelService modelService;
    private MockedStatic<ModelManager> models;

    private static MetaField relation(String name, FieldType type, String relatedModel, String relatedField) {
        // Setters are package-private: metadata is loaded, never built by hand outside the package.
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "fieldName", name);
        ReflectionTestUtils.setField(field, "fieldType", type);
        ReflectionTestUtils.setField(field, "relatedModel", relatedModel);
        ReflectionTestUtils.setField(field, "relatedField", relatedField);
        return field;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ChangeLogServiceImpl();
        permissionService = mock(PermissionService.class);
        modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "modelService", modelService);
        when(permissionService.getUserBlockedModelFields(any(), eq(AccessType.READ))).thenReturn(Set.of());

        MetaModel employee = mock(MetaModel.class);
        when(employee.isTimeline()).thenReturn(false);
        MetaField profile = relation("employeeProfileId", FieldType.ONE_TO_ONE, "EmployeeProfile", null);
        MetaField members = relation("empFamilyMembers", FieldType.ONE_TO_MANY, "EmpFamilyMember", "employeeId");
        MetaField salary = relation("empSalaryProfileItems", FieldType.ONE_TO_MANY, "EmpSalaryProfileItem", "employeeId");
        MetaField entity = relation("legalEntityId", FieldType.MANY_TO_ONE, "Company", null);

        models = mockStatic(ModelManager.class);
        models.when(() -> ModelManager.getModel("Employee")).thenReturn(employee);
        models.when(() -> ModelManager.getModelFieldOrNull("Employee", "employeeProfileId")).thenReturn(profile);
        models.when(() -> ModelManager.getModelField("Employee", "employeeProfileId")).thenReturn(profile);
        models.when(() -> ModelManager.getModelFieldOrNull("Employee", "empFamilyMembers")).thenReturn(members);
        models.when(() -> ModelManager.getModelFieldOrNull("Employee", "empSalaryProfileItems")).thenReturn(salary);
        models.when(() -> ModelManager.getModelFieldOrNull("Employee", "legalEntityId")).thenReturn(entity);
        // Row ids arrive from the index as strings and are typed by the model's id field.
        models.when(() -> ModelManager.getModelField("Employee", "id"))
                .thenReturn(relation("id", FieldType.LONG, null, null));
        models.when(() -> ModelManager.getModelStoredFields("EmpSalaryProfileItem"))
                .thenReturn(List.of("id", "employeeId", "amount", "note"));

        // Related models are plain unless a test says otherwise; a salary profile is a timeline.
        MetaModel plain = mock(MetaModel.class);
        when(plain.isTimeline()).thenReturn(false);
        MetaModel timeline = mock(MetaModel.class);
        when(timeline.isTimeline()).thenReturn(true);
        models.when(() -> ModelManager.getModel("EmpFamilyMember")).thenReturn(plain);
        models.when(() -> ModelManager.getModel("EmployeeProfile")).thenReturn(plain);
        models.when(() -> ModelManager.getModel("EmpSalaryProfileItem")).thenReturn(timeline);

        when(modelService.getIds(eq("EmpFamilyMember"), any(Filters.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(301L, 302L));
        when(modelService.getIds(eq("EmployeeProfile"), any(Filters.class))).thenReturn(List.of(200L));
        when(modelService.getById(eq("Employee"), eq(EMPLOYEE), any(Collection.class)))
                .thenReturn(Optional.of(Map.of("employeeProfileId", Map.of("id", 200L, "displayName", "Ada"))));
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    @Test
    void gathersTheRecordAndTheRowsItsRelationsHold() {
        List<ChangeLogServiceImpl.HistoryPart> parts =
                service.historyParts("Employee", EMPLOYEE, List.of("employeeProfileId", "empFamilyMembers"));

        assertThat(parts).containsExactly(
                new ChangeLogServiceImpl.HistoryPart("Employee", List.of("100"), null, null),
                new ChangeLogServiceImpl.HistoryPart("EmpFamilyMember", List.of("301", "302"), "employeeId=100", null),
                new ChangeLogServiceImpl.HistoryPart("EmployeeProfile", List.of("200"), null, null));
    }

    @Test
    void findsAOneToManyRowByReferenceSoADeletedOneIsNotLost() {
        // The ids are the rows that exist now; the reference is what still names a deleted row's
        // logs, and both are asked: the reference alone would miss logs written before references.
        ChangeLogServiceImpl.HistoryPart members =
                service.historyParts("Employee", EMPLOYEE, List.of("empFamilyMembers")).get(1);

        assertThat(members.ref()).isEqualTo("employeeId=100");
        assertThat(members.rowIds()).containsExactly("301", "302");
    }

    @Test
    void asksATimelineChildByEverySliceNotByItsBusinessId() {
        // A salary profile row is one business id cut into slices by effective date, and each
        // slice is logged under its own id. Asked by the business id, its history came back empty —
        // two salary edits made and logged, and nothing in the employee's list.
        when(modelService.searchList(eq("EmpSalaryProfileItem"), any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of(Map.of("sliceId", 880773695407136797L), Map.of("sliceId", 879762779966280462L)));

        ChangeLogServiceImpl.HistoryPart salary =
                service.historyParts("Employee", EMPLOYEE, List.of("empSalaryProfileItems")).get(1);

        assertThat(salary.rowIds()).containsExactly("880773695407136797", "879762779966280462");
        verify(modelService, never()).getIds(eq("EmpSalaryProfileItem"), any(Filters.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void asksAnUnboundedRelationByReferenceAlone() {
        // A ledger of balance events grows without end; past the limit its current ids are not
        // spelled out on every read, and the reference — which every recent log carries — stands alone.
        List<Serializable> many = new java.util.ArrayList<>();
        for (long i = 0; i <= ChangeLogServiceImpl.CURRENT_ROWS_ASKED_BY_ID; i++) many.add(1000L + i);
        when(modelService.getIds(eq("EmpFamilyMember"), any(Filters.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(many);

        ChangeLogServiceImpl.HistoryPart members =
                service.historyParts("Employee", EMPLOYEE, List.of("empFamilyMembers")).get(1);

        assertThat(members.rowIds()).isEmpty();
        assertThat(members.ref()).isEqualTo("employeeId=100");
    }

    @Test
    void refusesAFieldThatIsNotAOneToOneOrOneToMany() {
        // A many-to-one points at a record with a history of its own — the company is not the
        // employee's — so it is refused rather than quietly folded in.
        assertThatThrownBy(() -> service.historyParts("Employee", EMPLOYEE, List.of("legalEntityId")))
                .hasMessageContaining("not a one-to-one or one-to-many");
    }

    @Test
    void leavesOutARelationWhoseModelTheReaderCannotRead() {
        // As the form leaves out the table: refusing the whole history over one relation would
        // hide everything the reader is entitled to.
        doThrow(new PermissionException("no read")).when(permissionService)
                .checkModelAccess("EmpFamilyMember", AccessType.READ);

        List<ChangeLogServiceImpl.HistoryPart> parts =
                service.historyParts("Employee", EMPLOYEE, List.of("empFamilyMembers"));

        assertThat(parts).extracting(ChangeLogServiceImpl.HistoryPart::model).containsExactly("Employee");
        verify(modelService, never()).getIds(eq("EmpFamilyMember"), any(Filters.class), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void namesTheFieldsAReaderMaySeeWhereTheirSetsHideSome() {
        // What the query needs to leave out an update that touched only hidden fields — from the
        // count, not just the page.
        when(permissionService.getUserBlockedModelFields("EmpSalaryProfileItem", AccessType.READ))
                .thenReturn(Set.of("amount"));
        when(modelService.searchList(eq("EmpSalaryProfileItem"), any(io.softa.framework.orm.domain.FlexQuery.class)))
                .thenReturn(List.of());

        ChangeLogServiceImpl.HistoryPart salary =
                service.historyParts("Employee", EMPLOYEE, List.of("empSalaryProfileItems")).get(1);

        assertThat(salary.visibleFields()).containsExactly("id", "employeeId", "note");
    }

    /**
     * Rows the record's model has no field to reach: an access card points at the employee, and
     * the employee lists no cards. Revoking one deletes its row, and its history went with it.
     */
    @Test
    void findsTheRowsOfAnotherModelThatPointAtTheRecordDeletedOnesIncluded() {
        pointing("EmpAccessCard", "employeeId", "Employee");
        when(modelService.getIds(eq("EmpAccessCard"), any(Filters.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(501L));

        List<ChangeLogServiceImpl.HistoryPart> parts =
                service.historyParts("Employee", EMPLOYEE, List.of(), List.of("EmpAccessCard.employeeId"));

        assertThat(parts).containsExactly(
                new ChangeLogServiceImpl.HistoryPart("Employee", List.of("100"), null, null),
                new ChangeLogServiceImpl.HistoryPart("EmpAccessCard", List.of("501"), "employeeId=100", null));
    }

    @Test
    void refusesAReferenceToAnotherModel() {
        // A field that points elsewhere would let one record's history read the logs of rows that
        // have nothing to do with it.
        pointing("EmpAccessCard", "companyId", "Company");

        assertThatThrownBy(() ->
                service.historyParts("Employee", EMPLOYEE, List.of(), List.of("EmpAccessCard.companyId")))
                .hasMessageContaining("not a many-to-one or one-to-one to Employee");
    }

    @Test
    void findsAOneToOneThatPointsAtTheRecordByItsRow() {
        // An employee's login identity points at them one-to-one: no reference in its logs, and no
        // deleted history to lose — its current row is the whole answer.
        MetaModel plain = mock(MetaModel.class);
        when(plain.isTimeline()).thenReturn(false);
        models.when(() -> ModelManager.getModel("EmpLogin")).thenReturn(plain);
        models.when(() -> ModelManager.getModelFieldOrNull("EmpLogin", "employeeId"))
                .thenReturn(relation("employeeId", FieldType.ONE_TO_ONE, "Employee", null));
        when(modelService.getIds(eq("EmpLogin"), any(Filters.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(601L));

        ChangeLogServiceImpl.HistoryPart login =
                service.historyParts("Employee", EMPLOYEE, List.of(), List.of("EmpLogin.employeeId")).get(1);

        assertThat(login.model()).isEqualTo("EmpLogin");
        assertThat(login.rowIds()).containsExactly("601");
    }

    @Test
    void refusesAReferenceThatIsNotModelDotField() {
        assertThatThrownBy(() ->
                service.historyParts("Employee", EMPLOYEE, List.of(), List.of("EmpAccessCard")))
                .hasMessageContaining("Model.field");
    }

    @Test
    void leavesOutReferencingRowsWhoseModelTheReaderCannotRead() {
        pointing("EmpAccessCard", "employeeId", "Employee");
        doThrow(new PermissionException("no read")).when(permissionService)
                .checkModelAccess("EmpAccessCard", AccessType.READ);

        List<ChangeLogServiceImpl.HistoryPart> parts =
                service.historyParts("Employee", EMPLOYEE, List.of(), List.of("EmpAccessCard.employeeId"));

        assertThat(parts).extracting(ChangeLogServiceImpl.HistoryPart::model).containsExactly("Employee");
    }

    private void pointing(String model, String field, String relatedModel) {
        MetaModel plain = mock(MetaModel.class);
        when(plain.isTimeline()).thenReturn(false);
        models.when(() -> ModelManager.getModel(model)).thenReturn(plain);
        models.when(() -> ModelManager.getModelFieldOrNull(model, field))
                .thenReturn(relation(field, FieldType.MANY_TO_ONE, relatedModel, null));
    }

    @Test
    void theQueryAsksEachPartByItsModelAndItsRows() {
        String query = service.historyQuery(List.of(
                new ChangeLogServiceImpl.HistoryPart("Employee", List.of("100"), null, null),
                new ChangeLogServiceImpl.HistoryPart("EmpFamilyMember", List.of("301"), "employeeId=100", null),
                new ChangeLogServiceImpl.HistoryPart("EmpSalaryProfileItem", List.of(), "employeeId=100",
                        List.of("note"))),
                false).toString();

        assertThat(query).contains("\"model\":{\"value\":\"Employee\"}");
        assertThat(query).contains("\"refs\":{\"value\":\"employeeId=100\"}");
        // The hidden-only update is excluded by what it wrote, so it is out of the count too.
        assertThat(query).contains("\"must_not\"").contains("changedFields");
        // Creation left out on request.
        assertThat(query).contains("\"UPDATE\"").contains("\"DELETE\"");
    }

    @Test
    void theQueryStaysInsideTheCallersTenant() {
        // One index for every tenant, and this query does not pass through searchPage.
        io.softa.framework.base.context.Context context = new io.softa.framework.base.context.Context();
        context.setTenantId(7L);
        String query = io.softa.framework.base.context.ContextHolder.callWith(context, () ->
                service.historyQuery(List.of(
                        new ChangeLogServiceImpl.HistoryPart("Employee", List.of("100"), null, null)),
                        true).toString());

        assertThat(query).contains("\"tenantId\":{\"value\":\"7\"}");
    }

    @Test
    void aReaderUnderNoRowScopeKeepsEveryLogADeletedRowsIncluded() {
        when(permissionService.appendScopeAccessFilters(eq("Employee"), any(Filters.class))).thenReturn(new Filters());
        List<ChangeLog> logs = List.of(log("100"), log("999"));

        assertThat(service.onReadableRows("Employee", logs)).isSameAs(logs);
        verify(modelService, never()).getIds(eq("Employee"), any(Filters.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aScopedReaderKeepsOnlyTheRowsInsideTheirScope() {
        when(permissionService.appendScopeAccessFilters(eq("Employee"), any(Filters.class)))
                .thenReturn(Filters.of("departmentId", io.softa.framework.base.enums.Operator.EQUAL, 1L));
        when(modelService.getIds(eq("Employee"), any(Filters.class))).thenReturn(List.<Serializable>of(100L));

        List<ChangeLog> visible = service.onReadableRows("Employee", List.of(log("100"), log("999")));

        assertThat(visible).extracting(ChangeLog::getRowId).containsExactly("100");
    }

    private static ChangeLog log(String rowId) {
        ChangeLog log = new ChangeLog();
        log.setModel("Employee");
        log.setRowId(rowId);
        return log;
    }
}
