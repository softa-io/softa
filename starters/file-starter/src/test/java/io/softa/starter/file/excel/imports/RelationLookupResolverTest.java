package io.softa.starter.file.excel.imports;

import java.util.*;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportFieldDTO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RelationLookupResolverTest {

    // ===== detectLookupGroups tests =====

    @Test
    void detectLookupGroupsSingleDottedPath() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();
            List<ImportFieldDTO> fields = List.of(importField("name", null), importField("deptId.code", null));
            List<RelationLookupResolver.LookupGroup> groups = resolver.detectLookupGroups("TestOrder", fields);
            assertEquals(1, groups.size());
            assertEquals("deptId", groups.getFirst().rootField());
            assertEquals("Department", groups.getFirst().relatedModel());
            assertEquals(List.of("code"), groups.getFirst().lookupFields());
            assertEquals(List.of("deptId.code"), groups.getFirst().dottedPaths());
        }
    }

    @Test
    void detectLookupGroupsCompositeLookupKey() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();
            List<ImportFieldDTO> fields = List.of(importField("name", null), importField("deptId.code", null), importField("deptId.name", null));
            List<RelationLookupResolver.LookupGroup> groups = resolver.detectLookupGroups("TestOrder", fields);
            assertEquals(1, groups.size());
            assertEquals(List.of("code", "name"), groups.getFirst().lookupFields());
        }
    }

    @Test
    void detectLookupGroupsMultipleRootFields() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();
            List<ImportFieldDTO> fields = List.of(importField("name", null), importField("deptId.code", null), importField("managerId.code", null));
            List<RelationLookupResolver.LookupGroup> groups = resolver.detectLookupGroups("TestOrder", fields);
            assertEquals(2, groups.size());
            assertEquals("deptId", groups.get(0).rootField());
            assertEquals("managerId", groups.get(1).rootField());
        }
    }

    @Test
    void detectLookupGroupsNoDottedPaths() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();
            List<ImportFieldDTO> fields = List.of(importField("name", null), importField("deptId", null));
            assertTrue(resolver.detectLookupGroups("TestOrder", fields).isEmpty());
        }
    }

    @Test
    void detectLookupGroupsRejectsMultipleDotLevels() {
        RelationLookupResolver resolver = createResolver();
        List<ImportFieldDTO> fields = List.of(importField("deptId.companyId.code", null));
        assertThrows(IllegalArgumentException.class, () -> resolver.detectLookupGroups("TestOrder", fields));
    }

    @Test
    void detectLookupGroupsRejectsDirectFKAndLookupConflict() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();
            List<ImportFieldDTO> fields = List.of(importField("deptId", null), importField("deptId.code", null));
            assertThrows(IllegalArgumentException.class, () -> resolver.detectLookupGroups("TestOrder", fields));
        }
    }

    @Test
    void detectLookupGroupsRejectsNonToOneRootField() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();
            List<ImportFieldDTO> fields = List.of(importField("status.code", null));
            assertThrows(IllegalArgumentException.class, () -> resolver.detectLookupGroups("TestOrder", fields));
        }
    }

    @Test
    void detectLookupGroupsDerivesIgnoreEmpty() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();
            assertTrue(resolver.detectLookupGroups("TestOrder", List.of(importField("deptId.code", true))).getFirst().ignoreEmpty());
            assertFalse(resolver.detectLookupGroups("TestOrder", List.of(importField("deptId.code", false))).getFirst().ignoreEmpty());
        }
    }

    // ===== resolveRows tests =====

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsWritesBackFKId() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup("deptId", "Department", List.of("code"), List.of("deptId.code"), true);

        Map<String, Object> row1 = new LinkedHashMap<>(Map.of("name", "Order1", "deptId.code", "D001"));
        Map<String, Object> row2 = new LinkedHashMap<>(Map.of("name", "Order2", "deptId.code", "D002"));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row1, row2));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Department"), eq(List.of("code")), anyCollection()))
                .thenReturn(Map.of(List.of("D001"), 100L, List.of("D002"), 200L));

        resolver.resolveRows(rows, List.of(group), true);

        assertEquals(100L, row1.get("deptId"));
        assertEquals(200L, row2.get("deptId"));
        assertFalse(row1.containsKey("deptId.code"));
        assertFalse(row2.containsKey("deptId.code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsSkipExceptionTrueMarksFailedWhenNotFound() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup("deptId", "Department", List.of("code"), List.of("deptId.code"), true);

        Map<String, Object> row = new LinkedHashMap<>(Map.of("name", "Order1", "deptId.code", "UNKNOWN"));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Department"), eq(List.of("code")), anyCollection()))
                .thenReturn(Collections.emptyMap());

        resolver.resolveRows(rows, List.of(group), true);

        assertTrue(row.containsKey(FileConstant.FAILED_REASON));
        assertTrue(row.get(FileConstant.FAILED_REASON).toString().contains("Cannot find Department"));
        assertFalse(row.containsKey("deptId.code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsSkipExceptionFalseThrowsWhenNotFound() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup("deptId", "Department", List.of("code"), List.of("deptId.code"), true);

        Map<String, Object> row = new LinkedHashMap<>(Map.of("name", "Order1", "deptId.code", "UNKNOWN"));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Department"), eq(List.of("code")), anyCollection()))
                .thenReturn(Collections.emptyMap());

        assertThrows(ValidationException.class, () -> resolver.resolveRows(rows, List.of(group), false));
    }

    @Test
    void resolveRowsIgnoreEmptyTrueSkipsEmptyValues() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup("deptId", "Department", List.of("code"), List.of("deptId.code"), true);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "Order1");
        row.put("deptId.code", "");
        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertFalse(row.containsKey("deptId"));
        assertFalse(row.containsKey("deptId.code"));
        assertFalse(row.containsKey(FileConstant.FAILED_REASON));
    }

    @Test
    void resolveRowsIgnoreEmptyFalseWritesNullForEmptyValues() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup("deptId", "Department", List.of("code"), List.of("deptId.code"), false);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "Order1");
        row.put("deptId.code", "");
        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertTrue(row.containsKey("deptId"));
        assertNull(row.get("deptId"));
        assertFalse(row.containsKey("deptId.code"));
    }

    @Test
    void resolveRowsIgnoreEmptyFalseWritesNullForNullValues() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup("deptId", "Department", List.of("code"), List.of("deptId.code"), false);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "Order1");
        row.put("deptId.code", null);
        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertTrue(row.containsKey("deptId"));
        assertNull(row.get("deptId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsIgnoreEmptyFalseAllRowsEmptyFastPath() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup("deptId", "Department", List.of("code"), List.of("deptId.code"), false);

        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("name", "Order1");
        row1.put("deptId.code", "");
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("name", "Order2");
        row2.put("deptId.code", null);
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row1, row2));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        resolver.resolveRows(rows, List.of(group), true);

        assertTrue(row1.containsKey("deptId"));
        assertNull(row1.get("deptId"));
        assertTrue(row2.containsKey("deptId"));
        assertNull(row2.get("deptId"));
        verify(typedService, never()).getIdsByBusinessKeys(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsIgnoreEmptyTrueAllRowsEmptyFastPath() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup("deptId", "Department", List.of("code"), List.of("deptId.code"), true);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "Order1");
        row.put("deptId.code", "");
        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertFalse(row.containsKey("deptId"));
        verify(typedService, never()).getIdsByBusinessKeys(any(), any(), any());
    }

    @Test
    void resolveRowsSkipsAlreadyFailedRows() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup("deptId", "Department", List.of("code"), List.of("deptId.code"), true);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "Order1");
        row.put("deptId.code", "D001");
        row.put(FileConstant.FAILED_REASON, "Previous error");
        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertFalse(row.containsKey("deptId.code"));
        assertEquals("Previous error", row.get(FileConstant.FAILED_REASON));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsCompositeLookupKey() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup("deptId", "Department", List.of("code", "name"), List.of("deptId.code", "deptId.name"), true);

        Map<String, Object> row = new LinkedHashMap<>(Map.of("deptId.code", "D001", "deptId.name", "Engineering"));
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Department"), eq(List.of("code", "name")), anyCollection()))
                .thenReturn(Map.of(List.of("D001", "Engineering"), 100L));

        resolver.resolveRows(rows, List.of(group), true);

        assertEquals(100L, row.get("deptId"));
        assertFalse(row.containsKey("deptId.code"));
        assertFalse(row.containsKey("deptId.name"));
    }

    @Test
    void resolveRowsIgnoreEmptyTrueSkipsNullValues() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup("deptId", "Department", List.of("code"), List.of("deptId.code"), true);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "Order1");
        row.put("deptId.code", null);
        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertFalse(row.containsKey("deptId"));
        assertFalse(row.containsKey("deptId.code"));
    }

    // ===== Helper methods =====

    private RelationLookupResolver createResolver() {
        RelationLookupResolver resolver = new RelationLookupResolver();
        ModelService<?> service = mock(ModelService.class);
        ReflectionTestUtils.setField(resolver, "modelService", service);
        return resolver;
    }

    private ModelService<?> getModelService(RelationLookupResolver resolver) {
        return (ModelService<?>) ReflectionTestUtils.getField(resolver, "modelService");
    }

    private void setupModelManager(MockedStatic<ModelManager> mm) {
        mm.when(() -> ModelManager.existField("TestOrder", "id")).thenReturn(true);
        mm.when(() -> ModelManager.existField("TestOrder", "name")).thenReturn(true);
        mm.when(() -> ModelManager.existField("TestOrder", "deptId")).thenReturn(true);
        mm.when(() -> ModelManager.existField("TestOrder", "managerId")).thenReturn(true);
        mm.when(() -> ModelManager.existField("TestOrder", "status")).thenReturn(true);
        mm.when(() -> ModelManager.existField("TestOrder", "nonExistent")).thenReturn(false);

        mm.when(() -> ModelManager.getModelField("TestOrder", "deptId"))
                .thenReturn(metaField("TestOrder", "deptId", FieldType.MANY_TO_ONE, "Department"));
        mm.when(() -> ModelManager.getModelField("TestOrder", "managerId"))
                .thenReturn(metaField("TestOrder", "managerId", FieldType.ONE_TO_ONE, "Employee"));
        mm.when(() -> ModelManager.getModelField("TestOrder", "status"))
                .thenReturn(metaField("TestOrder", "status", FieldType.OPTION, null));
    }

    private ImportFieldDTO importField(String fieldName, Boolean ignoreEmpty) {
        ImportFieldDTO dto = new ImportFieldDTO();
        dto.setFieldName(fieldName);
        dto.setHeader(fieldName);
        dto.setIgnoreEmpty(ignoreEmpty);
        return dto;
    }

    private MetaField metaField(String modelName, String fieldName, FieldType fieldType, String relatedModel) {
        MetaField mf = new MetaField();
        ReflectionTestUtils.setField(mf, "modelName", modelName);
        ReflectionTestUtils.setField(mf, "fieldName", fieldName);
        ReflectionTestUtils.setField(mf, "fieldType", fieldType);
        ReflectionTestUtils.setField(mf, "labelName", fieldName);
        if (relatedModel != null) {
            ReflectionTestUtils.setField(mf, "relatedModel", relatedModel);
        }
        return mf;
    }
}
