package io.softa.framework.orm.service.impl;

import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.utils.SFunction;
import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An entity read by id does not load its to-many fields — they are not columns — so they are null on it. Written
 * back with {@code ignoreNull = false}, the way a service writes the nulls it means, that null reached the relation
 * processors as "delete every link": re-inviting or re-syncing an account took all its roles away. A null to-many
 * field is left out of the row now; an empty list still clears.
 */
class EntityUpdateUnloadedRelationsTest {

    @Data
    @EqualsAndHashCode(callSuper = false)
    public static class Account extends AbstractModel {
        @Serial
        private static final long serialVersionUID = 1L;
        private Long id;
        private String email;
        private Long profileId;
        private List<Long> roles;
        private List<Account> lines;
    }

    static class AccountService extends EntityServiceImpl<Account, Long> {
    }

    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);
    private final AccountService service = new AccountService();
    private MockedStatic<ModelManager> models;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "modelService", modelService);
        models = Mockito.mockStatic(ModelManager.class);
        models.when(() -> ModelManager.getModelFieldOrNull(eq("Account"), anyString())).thenAnswer(inv -> switch ((String) inv.getArgument(1)) {
            case "roles" -> field("roles", FieldType.MANY_TO_MANY);
            case "lines" -> related(field("lines", FieldType.ONE_TO_MANY), "Account");
            case "email" -> field("email", FieldType.STRING);
            case "profileId" -> field("profileId", FieldType.MANY_TO_ONE);
            default -> null;
        });
        when(modelService.updateOne(anyString(), anyMap())).thenAnswer(inv -> {
            written.add(inv.getArgument(1));
            return true;
        });
        when(modelService.updateList(anyString(), anyList())).thenAnswer(inv -> {
            written.addAll(inv.getArgument(1));
            return true;
        });
        when(modelService.updateOneAndFetch(anyString(), anyMap(), any(ConvertType.class))).thenAnswer(inv -> {
            written.add(inv.getArgument(1));
            return new HashMap<>(Map.of("id", 7L));
        });
        when(modelService.updateListAndFetch(anyString(), anyList(), any(ConvertType.class))).thenAnswer(inv -> {
            written.addAll(inv.getArgument(1));
            return List.of();
        });
        when(modelService.createOrUpdate(anyString(), anyList(), anyList())).thenAnswer(inv -> {
            written.addAll(inv.getArgument(1));
            return null;
        });
    }

    /** Every row the service handed to the model service, whichever write it went through. */
    private final List<Map<String, Object>> written = new ArrayList<>();

    @AfterEach
    void tearDown() {
        models.close();
    }

    private static MetaField field(String name, FieldType type) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "fieldName", name);
        ReflectionTestUtils.setField(field, "fieldType", type);
        return field;
    }

    private static MetaField related(MetaField field, String relatedModel) {
        ReflectionTestUtils.setField(field, "relatedModel", relatedModel);
        return field;
    }

    private static Account loaded() {
        Account account = new Account();
        account.setId(7L);
        account.setEmail("wrong@example.com");
        account.setProfileId(3L);
        return account;
    }

    @Test
    @SuppressWarnings("unchecked")
    void writingNullsBackLeavesTheUnloadedToManyFieldsOut() {
        Account account = loaded();
        account.setProfileId(null);
        account.setEmail(null);

        service.updateOne(account, false);

        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(modelService).updateOne(eq("Account"), row.capture());
        assertThat(row.getValue())
                .containsEntry("profileId", null)
                .containsEntry("email", null)
                .doesNotContainKeys("roles", "lines");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEmptyListStillClears() {
        Account account = loaded();
        account.setRoles(new ArrayList<>());

        service.updateOne(account, false);

        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(modelService).updateOne(eq("Account"), row.capture());
        assertThat(row.getValue()).containsEntry("roles", List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aListWriteLeavesThemOutOfEveryRow() {
        service.updateList(new ArrayList<>(List.of(loaded(), loaded())), false);

        ArgumentCaptor<List<Map<String, Object>>> rows = ArgumentCaptor.forClass(List.class);
        verify(modelService).updateList(eq("Account"), rows.capture());
        assertThat(rows.getValue()).allSatisfy(row -> assertThat(row).doesNotContainKeys("roles", "lines"));
    }

    /**
     * A loaded child written back with its parent carries the same unloaded to-many fields; they are left out of the
     * child row too, while the child's own cleared column is still written.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aChildWrittenWithItsParentLeavesItsUnloadedToManyFieldsOut() {
        Account child = loaded();
        child.setEmail(null);
        Account account = loaded();
        account.setLines(new ArrayList<>(List.of(child)));

        service.updateOne(account, false);

        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(modelService).updateOne(eq("Account"), row.capture());
        List<Map<String, Object>> lines = (List<Map<String, Object>>) row.getValue().get("lines");
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line).doesNotContainKeys("roles", "lines");
            assertThat(line).containsEntry("email", null);
        });
    }

    /** One entity write path of the service, named for the test report. */
    record WritePath(String name, Consumer<AccountService> write) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<WritePath> everyEntityWrite() {
        SFunction<Account, ?> byEmail = Account::getEmail;
        return Stream.of(
                new WritePath("updateOne", s -> s.updateOne(loadedWithEmailCleared(), false)),
                new WritePath("updateOneAndFetch", s -> s.updateOneAndFetch(loadedWithEmailCleared(), false)),
                new WritePath("updateList", s -> s.updateList(new ArrayList<>(List.of(loadedWithEmailCleared(), loadedWithEmailCleared())), false)),
                new WritePath("updateListAndFetch",
                        s -> s.updateListAndFetch(new ArrayList<>(List.of(loadedWithEmailCleared(), loadedWithEmailCleared())), false)),
                new WritePath("createOrUpdate", s -> s.createOrUpdate(new ArrayList<>(List.of(loadedWithEmailCleared())), false)),
                new WritePath("createOrUpdate by unique fields",
                        s -> s.createOrUpdate(new ArrayList<>(List.of(loadedWithEmailCleared())), List.of(byEmail), false)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyEntityWrite")
    void everyEntityWriteLeavesTheUnloadedToManyFieldsOut(WritePath path) {
        path.write().accept(service);

        // Each path reaches the model service, and none of its rows carries the unloaded relations as a clear —
        // while a plain null on the same row is still written, as ignoreNull = false asks.
        assertThat(written).isNotEmpty().allSatisfy(row -> {
            assertThat(row).doesNotContainKeys("roles", "lines");
            assertThat(row).containsEntry("email", null);
        });
    }

    /** {@link #loaded()} with a column nulled, as a service does before writing a cleared value back. */
    private static Account loadedWithEmailCleared() {
        Account account = loaded();
        account.setEmail(null);
        return account;
    }
}
