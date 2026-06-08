package io.softa.starter.file.excel.imports;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.dto.ImportFieldDTO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationLookupResolverTest {

    @Test
    void detectLookupGroupsSupportsToOneAndToManyRootFields() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();

            List<RelationLookupResolver.LookupGroup> groups = resolver.detectLookupGroups(
                    "TestOrder", List.of(importField("deptId.code", null), importField("roleIds.code", null)));

            assertEquals(2, groups.size());
            assertFalse(groups.getFirst().toMany());
            assertTrue(groups.get(1).toMany());
        }
    }

    @Test
    void detectLookupGroupsRejectsNonRelationRootField() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            setupModelManager(mm);
            RelationLookupResolver resolver = createResolver();

            assertThrows(IllegalArgumentException.class,
                    () -> resolver.detectLookupGroups("TestOrder", List.of(importField("status.code", null))));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsToOneWritesBackFkId() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "deptId", "Department", List.of("code"), List.of("deptId.code"), true, false);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("deptId.code", "D001"));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Department"), eq(List.of("code")), anyCollection()))
                .thenReturn(Map.of(List.of("D001"), 100L));

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(100L, row.get("deptId"));
        assertFalse(row.containsKey("deptId.code"));
    }

    @Test
    void resolveRowsToOneIgnoreEmptyFalseWritesNull() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "deptId", "Department", List.of("code"), List.of("deptId.code"), false, false);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deptId.code", "");

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertTrue(row.containsKey("deptId"));
        assertNull(row.get("deptId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsToManyWritesBackIdList() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "roleIds", "Role", List.of("code"), List.of("roleIds.code"), true, true);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("roleIds.code", "ADMIN,USER"));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Role"), eq(List.of("code")), anyCollection()))
                .thenReturn(Map.of(List.of("ADMIN"), 11L, List.of("USER"), 12L));

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(List.of(11L, 12L), row.get("roleIds"));
        assertFalse(row.containsKey("roleIds.code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsToManyByNameWritesBackIdList() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "roleIds", "Role", List.of("name"), List.of("roleIds.name"), true, true);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("roleIds.name", "Admin,User"));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Role"), eq(List.of("name")), anyCollection()))
                .thenReturn(Map.of(List.of("Admin"), 21L, List.of("User"), 22L));

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(List.of(21L, 22L), row.get("roleIds"));
        assertFalse(row.containsKey("roleIds.name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsToManyByCompositeBusinessKeysWritesBackIdList() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "roleIds", "Role", List.of("code", "name"), List.of("roleIds.code", "roleIds.name"), true, true);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("roleIds.code", "ADMIN,USER");
        row.put("roleIds.name", "Admin,User");

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Role"), eq(List.of("code", "name")), anyCollection()))
                .thenReturn(Map.of(List.of("ADMIN", "Admin"), 31L, List.of("USER", "User"), 32L));

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(List.of(31L, 32L), row.get("roleIds"));
        assertFalse(row.containsKey("roleIds.code"));
        assertFalse(row.containsKey("roleIds.name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRowsToManyMarksFailedWhenCodeNotFound() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "roleIds", "Role", List.of("code"), List.of("roleIds.code"), true, true);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("roleIds.code", "ADMIN,UNKNOWN"));

        ModelService<Long> typedService = (ModelService<Long>) getModelService(resolver);
        when(typedService.getIdsByBusinessKeys(eq("Role"), eq(List.of("code")), anyCollection()))
                .thenReturn(Map.of(List.of("ADMIN"), 11L));

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertTrue(row.containsKey(FileConstant.FAILED_REASON));
        assertTrue(row.get(FileConstant.FAILED_REASON).toString().contains("Cannot find Role by code=UNKNOWN"));
        assertFalse(row.containsKey("roleIds"));
    }

    @Test
    void resolveRowsToManyIgnoreEmptyFalseWritesEmptyList() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "roleIds", "Role", List.of("code"), List.of("roleIds.code"), false, true);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("roleIds.code", "");

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(Collections.emptyList(), row.get("roleIds"));
        assertFalse(row.containsKey("roleIds.code"));
    }

    @Test
    void resolveRowsToManyIgnoreEmptyFalseWithNameWritesEmptyList() {
        RelationLookupResolver resolver = createResolver();
        var group = new RelationLookupResolver.LookupGroup(
                "roleIds", "Role", List.of("name"), List.of("roleIds.name"), false, true);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("roleIds.name", "");

        resolver.resolveRows(new ArrayList<>(List.of(row)), List.of(group), true);

        assertEquals(Collections.emptyList(), row.get("roleIds"));
        assertFalse(row.containsKey("roleIds.name"));
    }

    private RelationLookupResolver createResolver() {
        RelationLookupResolver resolver = new RelationLookupResolver();
        ReflectionTestUtils.setField(resolver, "modelService", mock(ModelService.class));
        return resolver;
    }

    private ModelService<?> getModelService(RelationLookupResolver resolver) {
        return (ModelService<?>) ReflectionTestUtils.getField(resolver, "modelService");
    }

    private void setupModelManager(MockedStatic<ModelManager> mm) {
        mm.when(() -> ModelManager.existField("TestOrder", "deptId")).thenReturn(true);
        mm.when(() -> ModelManager.existField("TestOrder", "roleIds")).thenReturn(true);
        mm.when(() -> ModelManager.existField("TestOrder", "status")).thenReturn(true);

        mm.when(() -> ModelManager.getModelField("TestOrder", "deptId"))
                .thenReturn(metaField("TestOrder", "deptId", FieldType.MANY_TO_ONE, "Department"));
        mm.when(() -> ModelManager.getModelField("TestOrder", "roleIds"))
                .thenReturn(metaField("TestOrder", "roleIds", FieldType.MANY_TO_MANY, "Role"));
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
        ReflectionTestUtils.setField(mf, "label", fieldName);
        if (relatedModel != null) {
            ReflectionTestUtils.setField(mf, "relatedModel", relatedModel);
        }
        return mf;
    }
}
