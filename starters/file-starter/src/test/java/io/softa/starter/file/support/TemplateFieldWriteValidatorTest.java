package io.softa.starter.file.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.framework.orm.service.validation.WriteValidationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An import or export template is checked against the model it names when it is saved, by the rules its run
 * applies — so a wrong field is refused where it is written, not when someone runs the template.
 */
class TemplateFieldWriteValidatorTest {

    private MockedStatic<ModelManager> models;
    private ModelService<?> modelService;
    private TemplateFieldWriteValidator validator;

    @BeforeEach
    void setUp() {
        models = Mockito.mockStatic(ModelManager.class);
        models.when(() -> ModelManager.existModel(anyString())).thenAnswer(call ->
                List.of("Employee", "Department", "Role", "Profile", "IdType").contains(call.<String>getArgument(0)));
        models.when(() -> ModelManager.existField(anyString(), anyString())).thenReturn(false);
        stub("Employee", "name", FieldType.STRING, null);
        stub("Employee", "code", FieldType.STRING, null);
        stub("Employee", "deptId", FieldType.MANY_TO_ONE, "Department");
        stub("Employee", "roleIds", FieldType.MANY_TO_MANY, "Role");
        stub("Employee", "profileId", FieldType.ONE_TO_ONE, "Profile");
        ReflectionTestUtils.setField(stub("Employee", "age", FieldType.INTEGER, null), "dynamic", true);
        stub("Department", "code", FieldType.STRING, null);
        stub("Department", "name", FieldType.STRING, null);
        stub("Department", "parentId", FieldType.MANY_TO_ONE, "Department");
        stub("Role", "code", FieldType.STRING, null);
        stub("Profile", "idType", FieldType.MANY_TO_ONE, "IdType");
        stub("IdType", "name", FieldType.STRING, null);
        modelService = mock(ModelService.class);
        validator = new TemplateFieldWriteValidator(modelService);
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    private MetaField stub(String model, String fieldName, FieldType type, String relatedModel) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", model);
        ReflectionTestUtils.setField(field, "fieldName", fieldName);
        ReflectionTestUtils.setField(field, "fieldType", type);
        ReflectionTestUtils.setField(field, "label", fieldName);
        ReflectionTestUtils.setField(field, "relatedModel", relatedModel);
        ReflectionTestUtils.setField(field, "dynamic", FieldType.TO_MANY_TYPES.contains(type));
        models.when(() -> ModelManager.existField(model, fieldName)).thenReturn(true);
        models.when(() -> ModelManager.getModelField(model, fieldName)).thenReturn(field);
        return field;
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private static List<String> messages(WriteContext ctx) {
        List<String> messages = new ArrayList<>();
        for (WriteValidationException.FieldError error : ctx.errors()) {
            messages.add(error.field() + " | " + error.message());
        }
        return messages;
    }

    private void templateNames(String templateModel, String targetModel) {
        when(modelService.searchList(eq(templateModel), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("id", 7L, "modelName", targetModel)));
    }

    @Test
    void anExportTemplatesFiltersAndOrdersAreCheckedAgainstItsOwnModel() {
        Map<String, Object> template = row("modelName", "Employee",
                "filters", "[[\"deptId.ghost\", \"=\", 1], \"AND\", [\"roleIds.code\", \"=\", \"HR\"]]",
                "orders", "name ASC, age DESC");
        WriteContext ctx = WriteContext.of("ExportTemplate", AccessType.CREATE, template, null);

        validator.validateCreate(ctx);

        // A condition crosses the to-many roles by a sub-query; an order cannot sort by a computed age.
        assertEquals(List.of(
                        "filters | 'deptId.ghost': Field 'ghost' not found on model 'Department'.",
                        "orders | 'age': 'age' is not stored, so there is no column to sort by."),
                messages(ctx));
    }

    @Test
    void aTemplateNamingNoModelIsRefusedOnItsModelAlone() {
        WriteContext ctx = WriteContext.of("ExportTemplate", AccessType.CREATE,
                row("modelName", "Ghost", "filters", "[[\"name\", \"=\", 1]]"), null);

        validator.validateCreate(ctx);

        assertEquals(List.of("modelName | Model 'Ghost' does not exist."), messages(ctx));
    }

    @Test
    void anUpdateThatSetsNothingTheChecksReadLeavesTheTemplateAlone() {
        Map<String, Object> stored = row("id", 7L, "modelName", "Employee", "filters", "[[\"ghost\", \"=\", 1]]");
        Map<String, Object> merged = new HashMap<>(stored);
        merged.put("fileName", "Staff");
        WriteContext ctx = WriteContext.of("ExportTemplate", AccessType.UPDATE, merged, row("fileName", "Staff"), stored);

        validator.validateUpdate(ctx);

        assertTrue(ctx.errors().isEmpty());
        verifyNoInteractions(modelService);
    }

    @Test
    void movingAnExportTemplateToAnotherModelRechecksTheColumnsItKeeps() {
        Map<String, Object> stored = row("id", 7L, "modelName", "Employee");
        Map<String, Object> merged = row("id", 7L, "modelName", "Department");
        // One column is rewritten by the same write, one deleted; those are checked as they are written.
        Map<String, Object> patch = row("modelName", "Department", "exportFields", Map.of(
                "Update", List.of(Map.of("id", 2L, "fieldName", "code")),
                "Delete", List.of(3L)));
        when(modelService.searchList(eq("ExportTemplateField"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("id", 1L, "fieldName", "name"),
                Map.of("id", 2L, "fieldName", "deptId.code"),
                Map.of("id", 3L, "fieldName", "roleIds"),
                Map.of("id", 4L, "fieldName", "parentId.name"),
                Map.of("id", 5L, "fieldName", "fullName")));
        WriteContext ctx = WriteContext.of("ExportTemplate", AccessType.UPDATE, merged, patch, stored);

        validator.validateUpdate(ctx);

        assertEquals(List.of("exportFields | 'fullName': Field 'fullName' not found on model 'Department'."),
                messages(ctx));
    }

    @Test
    void anExportColumnIsReadThroughToOneRelationsOfItsTemplatesModel() {
        templateNames("ExportTemplate", "Employee");
        Map<String, Object> scratch = new HashMap<>();
        WriteContext good = WriteContext.of("ExportTemplateField", AccessType.CREATE,
                row("templateId", 7L, "fieldName", "deptId.parentId.name"), row(), null, scratch);
        WriteContext bad = WriteContext.of("ExportTemplateField", AccessType.CREATE,
                row("templateId", 7L, "fieldName", "roleIds.code"), row(), null, scratch);

        validator.validateCreate(good);
        validator.validateCreate(bad);

        assertTrue(good.errors().isEmpty());
        assertEquals(List.of("fieldName | 'roleIds.code': Cannot cascade through 'roleIds' (ManyToMany)."),
                messages(bad));
        // One read of the template for the write's rows.
        verify(modelService, times(1)).searchList(eq("ExportTemplate"), any(FlexQuery.class));
    }

    @Test
    void anImportColumnIsHeldToTheImportersOwnRules() {
        templateNames("ImportTemplate", "Employee");
        Map<String, Object> scratch = new HashMap<>();
        WriteContext toManyRoot = WriteContext.of("ImportTemplateField", AccessType.CREATE,
                row("templateId", 7L, "fieldName", "roleIds.code"), row(), null, scratch);
        WriteContext subRecordLookup = WriteContext.of("ImportTemplateField", AccessType.CREATE,
                row("templateId", 7L, "fieldName", "profileId.idType.name"), row(), null, scratch);
        WriteContext throughManyToOne = WriteContext.of("ImportTemplateField", AccessType.CREATE,
                row("templateId", 7L, "fieldName", "deptId.parentId.name"), row(), null, scratch);
        WriteContext computed = WriteContext.of("ImportTemplateField", AccessType.CREATE,
                row("templateId", 7L, "fieldName", "age"), row(), null, scratch);

        List.of(toManyRoot, subRecordLookup, throughManyToOne, computed).forEach(validator::validateCreate);

        // A cell may name roles by code, and the profile's ID type by name — the import resolves both.
        assertTrue(toManyRoot.errors().isEmpty());
        assertTrue(subRecordLookup.errors().isEmpty());
        assertEquals(List.of("fieldName | 'deptId.parentId.name': Import field `deptId.parentId.name`: a two-level "
                        + "path is only supported through a one-to-one sub-record of the row itself, but `deptId` "
                        + "is `ManyToOne`."),
                messages(throughManyToOne));
        assertEquals(List.of("fieldName | 'age': The last field age in import field age must be a stored field in "
                        + "model Employee!"),
                messages(computed));
    }

    @Test
    void anImportTemplatesUniqueKeyMustBeSomethingItCanLookUp() {
        WriteContext ctx = WriteContext.of("ImportTemplate", AccessType.CREATE,
                row("modelName", "Employee", "uniqueConstraints", "code, ghost"), null);

        validator.validateCreate(ctx);

        assertEquals(List.of("uniqueConstraints | 'ghost': Field 'ghost' not found on model 'Employee'."),
                messages(ctx));
    }

    /** The columns stored for template 7: a relation imported directly, and a plain field. */
    private void storedImportColumns() {
        when(modelService.searchList(eq("ImportTemplateField"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("id", 1L, "fieldName", "deptId"),
                Map.of("id", 2L, "fieldName", "name")));
    }

    private WriteContext importColumnsUpdate(Object importFields) {
        Map<String, Object> stored = row("id", 7L, "modelName", "Employee");
        Map<String, Object> patch = row("importFields", importFields);
        Map<String, Object> merged = new HashMap<>(stored);
        merged.putAll(patch);
        return WriteContext.of("ImportTemplate", AccessType.UPDATE, merged, patch, stored);
    }

    @Test
    void anImportTemplatesColumnsAreCheckedTogetherAsTheWriteLeavesThem() {
        storedImportColumns();
        WriteContext addsALookup = importColumnsUpdate(Map.of("Create", List.of(Map.of("fieldName", "deptId.code"))));
        WriteContext repaths = importColumnsUpdate(Map.of("Update", List.of(Map.of("id", 2L, "fieldName", "deptId.name"))));
        // A list restates the columns: the stored one it names by id keeps its path.
        WriteContext restates = importColumnsUpdate(List.of(Map.of("id", 1L), Map.of("fieldName", "deptId.code")));

        List.of(addsALookup, repaths, restates).forEach(validator::validateUpdate);

        String conflict = "importFields | Import field `deptId` and `[%s]` cannot coexist. Either import the "
                + "relation value directly or use relation lookup, not both.";
        assertEquals(List.of(conflict.formatted("deptId.code")), messages(addsALookup));
        assertEquals(List.of(conflict.formatted("deptId.name")), messages(repaths));
        assertEquals(List.of(conflict.formatted("deptId.code")), messages(restates));
    }

    /**
     * Turning a directly imported relation into a lookup in one save is fine, though its rows are then written one
     * batch after another — the new lookup created while the direct column still stands, before it is deleted.
     */
    @Test
    void aSaveThatReplacesADirectRelationWithALookupIsJudgedOnTheResult() {
        storedImportColumns();
        WriteContext replaces = importColumnsUpdate(Map.of(
                "Delete", List.of(1L),
                "Create", List.of(Map.of("fieldName", "deptId.code"))));
        WriteContext clears = importColumnsUpdate(null);

        validator.validateUpdate(replaces);
        validator.validateUpdate(clears);

        assertTrue(replaces.errors().isEmpty());
        assertTrue(clears.errors().isEmpty());
    }

    @Test
    void aNewImportTemplateMayNotWriteOneSubRecordFieldTwice() {
        WriteContext ctx = WriteContext.of("ImportTemplate", AccessType.CREATE, row("modelName", "Employee",
                "importFields", List.of(Map.of("fieldName", "profileId.idType"), Map.of("fieldName", "profileId.idType.name"))),
                null);

        validator.validateCreate(ctx);

        assertEquals(List.of("importFields | Import fields address `profileId.idType` more than once; a sub-record "
                + "field can be written by only one column."), messages(ctx));
        // Nothing is stored yet to read.
        verifyNoInteractions(modelService);
    }

    @Test
    void aColumnWhoseTemplateNamesNoModelIsLeftToTheTemplatesOwnCheck() {
        templateNames("ExportTemplate", "Ghost");
        WriteContext ctx = WriteContext.of("ExportTemplateField", AccessType.CREATE,
                row("templateId", 7L, "fieldName", "anything"), null);

        validator.validateCreate(ctx);

        assertTrue(ctx.errors().isEmpty());
    }

    @Test
    void aColumnUpdateThatTouchesNeitherItsFieldNorItsTemplateIsNotChecked() {
        WriteContext ctx = WriteContext.of("ExportTemplateField", AccessType.UPDATE,
                row("id", 1L, "templateId", 7L, "fieldName", "ghost", "customHeader", "X"),
                row("customHeader", "X"), row("id", 1L, "templateId", 7L, "fieldName", "ghost"));

        validator.validateUpdate(ctx);

        assertTrue(ctx.errors().isEmpty());
        verifyNoInteractions(modelService);
    }
}
