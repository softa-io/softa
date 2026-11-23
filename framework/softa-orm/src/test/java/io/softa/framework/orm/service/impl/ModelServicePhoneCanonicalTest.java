package io.softa.framework.orm.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.enums.WidgetType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A phone field reaches the code-layer rules in the spelling it is stored in. They run before the pipeline that
 * stores it, and one comparing the typed number with the stored one — is it already someone's? — saw
 * "+640211234678" and "+64211234678" as two numbers.
 */
class ModelServicePhoneCanonicalTest {

    private static MetaField field(String name, WidgetType widget) {
        MetaField field = Mockito.mock(MetaField.class);
        Mockito.when(field.getFieldName()).thenReturn(name);
        Mockito.when(field.getWidgetType()).thenReturn(widget);
        return field;
    }

    private Map<String, Object> canonicalize(Map<String, Object> row) {
        // Built before the static stub: stubbing one mock inside another's stubbing is unfinished stubbing.
        List<MetaField> fields = List.of(field("workPhone", WidgetType.PHONE), field("remarks", null));
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(fields);
            ReflectionTestUtils.invokeMethod(new ModelServiceImpl<Long>(), "canonicalizePhones", "Employee",
                    List.of(row));
        }
        return row;
    }

    @Test
    void aNumberTypedWithItsTrunkPrefixReachesTheRulesInItsStoredSpelling() {
        assertThat(canonicalize(new HashMap<>(Map.of("workPhone", "+640211234678"))))
                .containsEntry("workPhone", "+64211234678");
        assertThat(canonicalize(new HashMap<>(Map.of("workPhone", " +64 021 123 4678 "))))
                .containsEntry("workPhone", "+64211234678");
    }

    @Test
    void whatIsNotANumberIsLeftForTheFieldsOwnCheck() {
        assertThat(canonicalize(new HashMap<>(Map.of("workPhone", "12345"))))
                .containsEntry("workPhone", "12345");
    }

    @Test
    void otherFieldsAreUntouched() {
        assertThat(canonicalize(new HashMap<>(Map.of("remarks", "+640211234678"))))
                .containsEntry("remarks", "+640211234678");
    }
}
