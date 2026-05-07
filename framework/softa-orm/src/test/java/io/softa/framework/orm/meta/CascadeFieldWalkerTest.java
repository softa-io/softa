package io.softa.framework.orm.meta;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.enums.FieldType;

import static org.junit.jupiter.api.Assertions.*;

class CascadeFieldWalkerTest {

    private static MetaField field(String modelName, String fieldName, FieldType type, String relatedModel) {
        MetaField metaField = new MetaField();
        metaField.setModelName(modelName);
        metaField.setFieldName(fieldName);
        metaField.setFieldType(type);
        metaField.setRelatedModel(relatedModel);
        return metaField;
    }

    @Test
    void singleSegmentScalarPathReturnsLeaf() {
        MetaField name = field("AppEnv", "name", FieldType.STRING, null);
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("AppEnv", "name")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelField("AppEnv", "name")).thenReturn(name);

            CascadeFieldWalker.Result result = CascadeFieldWalker.walk("AppEnv", "name", CascadeFieldWalker.Visitor.NOOP);

            CascadeFieldWalker.Result.Ok ok = assertInstanceOf(CascadeFieldWalker.Result.Ok.class, result);
            assertSame(name, ok.leaf());
        }
    }

    @Test
    void singleSegmentRelationPathIsAllowedAtLeaf() {
        MetaField rel = field("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);

            CascadeFieldWalker.Result result = CascadeFieldWalker.walk("AppEnv", "lastDeploymentId", CascadeFieldWalker.Visitor.NOOP);

            assertInstanceOf(CascadeFieldWalker.Result.Ok.class, result);
        }
    }

    @Test
    void depth2ValidPathInvokesAdvanceOnce() {
        MetaField rel = field("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        MetaField status = field("DesignDeployment", "deployStatus", FieldType.OPTION, null);
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            modelManager.when(() -> ModelManager.existField("DesignDeployment", "deployStatus")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);
            modelManager.when(() -> ModelManager.getModelField("DesignDeployment", "deployStatus")).thenReturn(status);

            List<String> segmentsSeen = new ArrayList<>();
            List<String> advancesSeen = new ArrayList<>();
            CascadeFieldWalker.Visitor visitor = new CascadeFieldWalker.Visitor() {
                @Override public void onSegment(int index, String currentModel, MetaField f) {
                    segmentsSeen.add(currentModel + "." + f.getFieldName());
                }
                @Override public void onAdvance(int index, MetaField f, String nextModel) {
                    advancesSeen.add(f.getFieldName() + "->" + nextModel);
                }
            };

            CascadeFieldWalker.Result result = CascadeFieldWalker.walk("AppEnv", "lastDeploymentId.deployStatus", visitor);

            CascadeFieldWalker.Result.Ok ok = assertInstanceOf(CascadeFieldWalker.Result.Ok.class, result);
            assertSame(status, ok.leaf());
            assertEquals(List.of("AppEnv.lastDeploymentId", "DesignDeployment.deployStatus"), segmentsSeen);
            assertEquals(List.of("lastDeploymentId->DesignDeployment"), advancesSeen);
        }
    }

    @Test
    void depth3ValidPathInvokesAdvanceTwice() {
        MetaField owner = field("AppEnv", "ownerId", FieldType.MANY_TO_ONE, "Employee");
        MetaField dept = field("Employee", "departmentId", FieldType.MANY_TO_ONE, "Department");
        MetaField name = field("Department", "name", FieldType.STRING, null);
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("AppEnv", "ownerId")).thenReturn(true);
            modelManager.when(() -> ModelManager.existField("Employee", "departmentId")).thenReturn(true);
            modelManager.when(() -> ModelManager.existField("Department", "name")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelField("AppEnv", "ownerId")).thenReturn(owner);
            modelManager.when(() -> ModelManager.getModelField("Employee", "departmentId")).thenReturn(dept);
            modelManager.when(() -> ModelManager.getModelField("Department", "name")).thenReturn(name);

            List<String> advances = new ArrayList<>();
            CascadeFieldWalker.Visitor visitor = new CascadeFieldWalker.Visitor() {
                @Override public void onAdvance(int index, MetaField f, String nextModel) {
                    advances.add(index + ":" + f.getFieldName() + "->" + nextModel);
                }
            };

            CascadeFieldWalker.Result result =
                    CascadeFieldWalker.walk("AppEnv", "ownerId.departmentId.name", visitor);

            assertInstanceOf(CascadeFieldWalker.Result.Ok.class, result);
            assertEquals(List.of("0:ownerId->Employee", "1:departmentId->Department"), advances);
        }
    }

    @Test
    void traverseThroughOneToManyFails() {
        MetaField team = field("AppEnv", "team", FieldType.ONE_TO_MANY, "Member");
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("AppEnv", "team")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelField("AppEnv", "team")).thenReturn(team);

            CascadeFieldWalker.Result result =
                    CascadeFieldWalker.walk("AppEnv", "team.assigneeId.email", CascadeFieldWalker.Visitor.NOOP);

            CascadeFieldWalker.Result.Failure failure = assertInstanceOf(CascadeFieldWalker.Result.Failure.class, result);
            assertEquals(CascadeFieldWalker.ErrorKind.TRAVERSE_TO_MANY, failure.kind());
            assertEquals(0, failure.errorAt());
            assertTrue(failure.message().contains("team"));
        }
    }

    @Test
    void traverseThroughManyToManyFails() {
        MetaField tags = field("Article", "tags", FieldType.MANY_TO_MANY, "Tag");
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("Article", "tags")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelField("Article", "tags")).thenReturn(tags);

            CascadeFieldWalker.Result result =
                    CascadeFieldWalker.walk("Article", "tags.name", CascadeFieldWalker.Visitor.NOOP);

            CascadeFieldWalker.Result.Failure failure = assertInstanceOf(CascadeFieldWalker.Result.Failure.class, result);
            assertEquals(CascadeFieldWalker.ErrorKind.TRAVERSE_TO_MANY, failure.kind());
            assertEquals(0, failure.errorAt());
        }
    }

    @Test
    void traverseThroughScalarFails() {
        MetaField name = field("AppEnv", "name", FieldType.STRING, null);
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("AppEnv", "name")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelField("AppEnv", "name")).thenReturn(name);

            CascadeFieldWalker.Result result =
                    CascadeFieldWalker.walk("AppEnv", "name.length", CascadeFieldWalker.Visitor.NOOP);

            CascadeFieldWalker.Result.Failure failure = assertInstanceOf(CascadeFieldWalker.Result.Failure.class, result);
            assertEquals(CascadeFieldWalker.ErrorKind.TRAVERSE_NON_RELATION, failure.kind());
            assertEquals(0, failure.errorAt());
        }
    }

    @Test
    void firstSegmentMissingFails() {
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("AppEnv", "ghost")).thenReturn(false);

            CascadeFieldWalker.Result result =
                    CascadeFieldWalker.walk("AppEnv", "ghost.something", CascadeFieldWalker.Visitor.NOOP);

            CascadeFieldWalker.Result.Failure failure = assertInstanceOf(CascadeFieldWalker.Result.Failure.class, result);
            assertEquals(CascadeFieldWalker.ErrorKind.FIELD_NOT_FOUND, failure.kind());
            assertEquals(0, failure.errorAt());
        }
    }

    @Test
    void lastSegmentMissingFails() {
        MetaField rel = field("AppEnv", "lastDeploymentId", FieldType.MANY_TO_ONE, "DesignDeployment");
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("AppEnv", "lastDeploymentId")).thenReturn(true);
            modelManager.when(() -> ModelManager.existField("DesignDeployment", "ghost")).thenReturn(false);
            modelManager.when(() -> ModelManager.getModelField("AppEnv", "lastDeploymentId")).thenReturn(rel);

            CascadeFieldWalker.Result result =
                    CascadeFieldWalker.walk("AppEnv", "lastDeploymentId.ghost", CascadeFieldWalker.Visitor.NOOP);

            CascadeFieldWalker.Result.Failure failure = assertInstanceOf(CascadeFieldWalker.Result.Failure.class, result);
            assertEquals(CascadeFieldWalker.ErrorKind.FIELD_NOT_FOUND, failure.kind());
            assertEquals(1, failure.errorAt());
        }
    }

    @Test
    void depthBeyondLimitIsRejected() {
        // CASCADE_LEVEL = 4, so a path with 6 segments (5 hops) exceeds the limit.
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            CascadeFieldWalker.Result result = CascadeFieldWalker.walk(
                    "AppEnv", "a.b.c.d.e.f", CascadeFieldWalker.Visitor.NOOP);

            CascadeFieldWalker.Result.Failure failure = assertInstanceOf(CascadeFieldWalker.Result.Failure.class, result);
            assertEquals(CascadeFieldWalker.ErrorKind.MAX_DEPTH_EXCEEDED, failure.kind());
            modelManager.verifyNoInteractions();
        }
    }

    @Test
    void depthAtLimitIsAccepted() {
        // CASCADE_LEVEL = 4, so a path with 5 segments (4 hops) is the maximum allowed.
        MetaField a = field("M0", "a", FieldType.MANY_TO_ONE, "M1");
        MetaField b = field("M1", "b", FieldType.MANY_TO_ONE, "M2");
        MetaField c = field("M2", "c", FieldType.MANY_TO_ONE, "M3");
        MetaField d = field("M3", "d", FieldType.MANY_TO_ONE, "M4");
        MetaField e = field("M4", "e", FieldType.STRING, null);
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("M0", "a")).thenReturn(true);
            modelManager.when(() -> ModelManager.existField("M1", "b")).thenReturn(true);
            modelManager.when(() -> ModelManager.existField("M2", "c")).thenReturn(true);
            modelManager.when(() -> ModelManager.existField("M3", "d")).thenReturn(true);
            modelManager.when(() -> ModelManager.existField("M4", "e")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelField("M0", "a")).thenReturn(a);
            modelManager.when(() -> ModelManager.getModelField("M1", "b")).thenReturn(b);
            modelManager.when(() -> ModelManager.getModelField("M2", "c")).thenReturn(c);
            modelManager.when(() -> ModelManager.getModelField("M3", "d")).thenReturn(d);
            modelManager.when(() -> ModelManager.getModelField("M4", "e")).thenReturn(e);

            CascadeFieldWalker.Result result =
                    CascadeFieldWalker.walk("M0", "a.b.c.d.e", CascadeFieldWalker.Visitor.NOOP);

            assertInstanceOf(CascadeFieldWalker.Result.Ok.class, result);
        }
    }

    @Test
    void emptyPathFails() {
        CascadeFieldWalker.Result result = CascadeFieldWalker.walk(
                "AppEnv", "", CascadeFieldWalker.Visitor.NOOP);

        CascadeFieldWalker.Result.Failure failure = assertInstanceOf(CascadeFieldWalker.Result.Failure.class, result);
        assertEquals(CascadeFieldWalker.ErrorKind.FIELD_NOT_FOUND, failure.kind());
    }

    @Test
    void nullPathFails() {
        CascadeFieldWalker.Result result = CascadeFieldWalker.walk(
                "AppEnv", null, CascadeFieldWalker.Visitor.NOOP);

        assertInstanceOf(CascadeFieldWalker.Result.Failure.class, result);
    }

    @Test
    void visitorIsNotInvokedAfterFailure() {
        MetaField team = field("AppEnv", "team", FieldType.ONE_TO_MANY, "Member");
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.existField("AppEnv", "team")).thenReturn(true);
            modelManager.when(() -> ModelManager.getModelField("AppEnv", "team")).thenReturn(team);

            List<Integer> advanceIndices = new ArrayList<>();
            CascadeFieldWalker.Visitor visitor = new CascadeFieldWalker.Visitor() {
                @Override public void onAdvance(int index, MetaField f, String nextModel) {
                    advanceIndices.add(index);
                }
            };

            CascadeFieldWalker.walk("AppEnv", "team.assigneeId.email", visitor);

            // The walker rejects the OneToMany BEFORE invoking onAdvance — failure isolation contract.
            assertTrue(advanceIndices.isEmpty());
        }
    }

    @Test
    void segmentCountMatchesDots() {
        assertEquals(0, CascadeFieldWalker.segmentCount(""));
        assertEquals(0, CascadeFieldWalker.segmentCount(null));
        assertEquals(1, CascadeFieldWalker.segmentCount("a"));
        assertEquals(2, CascadeFieldWalker.segmentCount("a.b"));
        assertEquals(3, CascadeFieldWalker.segmentCount("a.b.c"));
    }

    @Test
    void segmentsHelperReturnsParts() {
        assertEquals(List.of("a", "b", "c"), CascadeFieldWalker.segments("a.b.c"));
        assertEquals(List.of(), CascadeFieldWalker.segments(""));
        assertEquals(List.of(), CascadeFieldWalker.segments(null));
    }

    @Test
    void leafIsNullForEmptyPathFailure() {
        // Defensive sanity check: ensure the failure type doesn't accidentally surface a leaf.
        CascadeFieldWalker.Result result = CascadeFieldWalker.walk(
                "AppEnv", "", CascadeFieldWalker.Visitor.NOOP);
        if (result instanceof CascadeFieldWalker.Result.Ok(MetaField leaf)) {
            assertNull(leaf, "Empty-path walks must not return Ok");
        }
    }
}
