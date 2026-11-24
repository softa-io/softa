package io.softa.framework.orm.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.domain.CreateOrUpdateResult;

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

    @Test
    void aRowMatchedByAPhoneKey_isLookedUpInItsStoredSpelling_andUpdatesTheRowItNames() {
        // createOrUpdate keyed on a phone: typed with its trunk prefix, the number must still find the
        // row stored without it, or it is created a second time beside it.
        MetaField phone = field("workPhone", WidgetType.PHONE);
        Mockito.when(phone.getFieldType()).thenReturn(FieldType.STRING);
        List<MetaField> fields = List.of(phone);
        ModelServiceImpl<Long> service = Mockito.spy(new ModelServiceImpl<Long>());
        List<Object> lookedUp = new java.util.ArrayList<>();
        Mockito.doAnswer(invocation -> {
            FlexQuery query = invocation.getArgument(1);
            lookedUp.add(query.getFilters().toString());
            return List.of(new HashMap<>(Map.of("id", 5L, "workPhone", "+64211234678")));
        }).when(service).searchList(Mockito.eq("Employee"), Mockito.any(FlexQuery.class));
        Map<String, Object> row = new HashMap<>(Map.of("workPhone", "+640211234678"));

        CreateOrUpdateResult split;
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(fields);
            mm.when(() -> ModelManager.getModelField("Employee", "workPhone")).thenReturn(phone);
            split = service.splitByExistence("Employee", List.of(row), List.of("workPhone"));
        }

        // Asked for under both spellings: a row still stored with the prefix is the same number.
        assertThat(lookedUp).singleElement().asString().contains("+64211234678").contains("+640211234678");
        assertThat(split.created()).isEmpty();
        assertThat(split.updated()).singleElement().satisfies(updated -> {
            assertThat(updated).containsEntry("id", 5L);
            assertThat(updated).containsEntry("workPhone", "+64211234678");
        });
    }

    private CreateOrUpdateResult splitAgainstStored(String stored, String typed) {
        MetaField phone = field("workPhone", WidgetType.PHONE);
        Mockito.when(phone.getFieldType()).thenReturn(FieldType.STRING);
        List<MetaField> fields = List.of(phone);
        ModelServiceImpl<Long> service = Mockito.spy(new ModelServiceImpl<Long>());
        Mockito.doAnswer(invocation -> {
            FlexQuery query = invocation.getArgument(1);
            return query.getFilters().toString().contains(stored)
                    ? List.of(new HashMap<>(Map.of("id", 5L, "workPhone", stored))) : List.of();
        }).when(service).searchList(Mockito.eq("Employee"), Mockito.any(FlexQuery.class));
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getModelFields("Employee")).thenReturn(fields);
            mm.when(() -> ModelManager.getModelField("Employee", "workPhone")).thenReturn(phone);
            return service.splitByExistence("Employee", List.of(new HashMap<>(Map.of("workPhone", typed))),
                    List.of("workPhone"));
        }
    }

    @Test
    void aRowStillStoredWithTheTrunkPrefix_isUpdated_whetherTheNumberIsTypedWithTheZeroOrWithout() {
        // Written before numbers were stored in one spelling, and not yet rewritten: the same number either way.
        for (String typed : List.of("+64211234678", "+640211234678", "+64 021 123 4678")) {
            CreateOrUpdateResult split = splitAgainstStored("+640211234678", typed);

            assertThat(split.created()).as(typed).isEmpty();
            assertThat(split.updated()).as(typed).singleElement()
                    .satisfies(updated -> assertThat(updated).containsEntry("id", 5L));
        }
    }

    @Test
    void aNumberWithNoTrunkPrefix_isLookedUpAsItIs() {
        CreateOrUpdateResult split = splitAgainstStored("+6591234567", "+65 9123 4567");

        assertThat(split.updated()).singleElement().satisfies(updated -> assertThat(updated).containsEntry("id", 5L));
        assertThat(splitAgainstStored("+6591234567", "+6591234568").created()).hasSize(1);
    }
}
