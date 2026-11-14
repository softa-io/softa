package io.softa.framework.orm.utils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

class IdUtilsTest {

    @BeforeAll
    static void ensureSystemConfig() {
        // The refusal below is a BaseException, whose message goes through I18n, which reads SystemConfig.
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    @Test
    void convertIdToLong() {
        assertNull(IdUtils.convertIdToLong(null));
        assertEquals(1L, IdUtils.convertIdToLong(1));
        assertEquals(1L, IdUtils.convertIdToLong(1));
        assertEquals(1L, IdUtils.convertIdToLong("1"));
        assertEquals(1L, IdUtils.convertIdToLong(1L));
    }

    /** A whole number converts whatever its numeric type: a driver or a JSON parser may hand over any. */
    @Test
    void aWholeNumberOfAnyNumericTypeConverts() {
        assertEquals(5L, IdUtils.convertIdToLong(BigInteger.valueOf(5)));
        assertEquals(5L, IdUtils.convertIdToLong(new BigDecimal("5")));
        assertEquals(5L, IdUtils.convertIdToLong(new BigDecimal("5.0")));
        assertEquals(5L, IdUtils.convertIdToLong((short) 5));
        assertEquals("5", IdUtils.convertIdToString(BigInteger.valueOf(5)));
    }

    @Test
    void aFractionOrANonNumberIsRefusedAsABadRequest() {
        assertThrows(IllegalArgumentException.class, () -> IdUtils.convertIdToLong(new BigDecimal("5.5")));
        assertThrows(IllegalArgumentException.class, () -> IdUtils.convertIdToLong(java.util.Map.of("id", 5)));
    }

    @Test
    void aBlankIdIsRefusedAsBlank() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> IdUtils.convertIdToLong(" "));
        assertTrue(refused.getMessage().contains("blank"), refused.getMessage());
    }

    /** A malformed id is the caller's mistake: refused as a bad request naming it, not a bare parse failure. */
    @Test
    void aStringThatIsNotANumberIsRefusedAsABadRequest() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> IdUtils.convertIdToLong("abc"));
        assertTrue(refused.getMessage().contains("abc"), refused.getMessage());
    }

    private static MetaField field(FieldType type, String relatedModel, FieldType relatedFieldType) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "fieldType", type);
        ReflectionTestUtils.setField(field, "relatedModel", relatedModel);
        ReflectionTestUtils.setField(field, "relatedFieldType", relatedFieldType);
        return field;
    }

    /**
     * A join model whose sides are relations: the ids a client sends are strings, and they have to come
     * out as the Long ids the join table holds, or a many-to-many Remove matches nothing.
     */
    @Test
    void aToOneFieldTakesTheTypeOfTheIdItReferences() {
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.getModelField("Rel", "userId"))
                    .thenReturn(field(FieldType.MANY_TO_ONE, "User", FieldType.LONG));
            models.when(() -> ModelManager.getModelField("Rel", "countryId"))
                    .thenReturn(field(FieldType.MANY_TO_ONE, "Country", FieldType.STRING));

            List<Serializable> userIds = IdUtils.formatIds("Rel", "userId", List.of("12", 13L));
            assertEquals(List.of(12L, 13L), userIds);
            assertEquals(12L, (Long) IdUtils.formatId("Rel", "userId", "12"));
            // A code-as-id master stays a string.
            assertEquals(List.of("SG"), IdUtils.formatIds("Rel", "countryId", List.of("SG")));
        }
    }

    @Test
    void aToOneFieldWithoutItsColumnTypeReadsTheReferencedId() {
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.getModelField("Rel", "roleId"))
                    .thenReturn(field(FieldType.ONE_TO_ONE, "Role", null));
            models.when(() -> ModelManager.getModelFieldOrNull("Role", ModelConstant.ID))
                    .thenReturn(field(FieldType.LONG, null, null));

            assertEquals(List.of(7L), IdUtils.formatIds("Rel", "roleId", List.of("7")));
        }
    }

    /** Neither the column type nor the referenced model to read it from: the ids pass as they come, as before. */
    @Test
    void aToOneFieldWhoseReferencedModelIsUnknownLeavesTheIdsAsTheyCome() {
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.getModelField("Rel", "groupId"))
                    .thenReturn(field(FieldType.MANY_TO_ONE, "Gone", null));
            models.when(() -> ModelManager.getModelFieldOrNull("Gone", ModelConstant.ID)).thenReturn(null);

            assertEquals(List.of("7"), IdUtils.formatIds("Rel", "groupId", List.of("7")));
            assertEquals("7", IdUtils.formatId("Rel", "groupId", "7"));
        }
    }

    @Test
    void aPlainIdFieldIsConvertedAsBefore() {
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.getModelField("Rel", ModelConstant.ID))
                    .thenReturn(field(FieldType.LONG, null, null));

            assertEquals(List.of(5L), IdUtils.formatIds("Rel", List.of("5")));
        }
    }
}
