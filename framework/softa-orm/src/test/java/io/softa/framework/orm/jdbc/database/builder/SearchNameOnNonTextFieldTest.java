package io.softa.framework.orm.jdbc.database.builder;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.database.DBUtil;
import io.softa.framework.orm.jdbc.database.SqlWrapper;
import io.softa.framework.orm.jdbc.database.dialect.PostgreSQLDialect;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A list's search box sends {@code searchName CONTAINS <typed text>}, and the virtual field resolves
 * per model to what {@code ModelManager.validateSearchName} settled: a declared {@code searchName},
 * else a field called {@code name}, else {@code id}.
 *
 * <p>The {@code id} branch used to build {@code t.id ILIKE '%...%'}. PostgreSQL refuses LIKE on a
 * BIGINT while parsing ({@code operator does not exist: bigint ~~* unknown}), whatever was typed, so
 * every list of such a model — over a hundred of them, the mail send log among them — answered any
 * keystroke in its search box with "System exception". A whole number now matches the id exactly,
 * anything else matches no row, and text members are searched as before.
 */
class SearchNameOnNonTextFieldTest {

    private static final String MODEL = "MailLog";
    private static final String TABLE = "mail_log";

    private MockedStatic<ModelManager> mm;
    private Object previousDialect;

    @BeforeEach
    void setUp() {
        previousDialect = ReflectionTestUtils.getField(DBUtil.class, "singleDbDialect");
        ReflectionTestUtils.setField(DBUtil.class, "singleDbDialect", new PostgreSQLDialect());
        mm = Mockito.mockStatic(ModelManager.class);
        mm.when(() -> ModelManager.isSoftDeleted(MODEL)).thenReturn(false);
        mm.when(() -> ModelManager.isActiveControl(MODEL)).thenReturn(false);
        mm.when(() -> ModelManager.isMultiTenantControl(Mockito.anyString())).thenReturn(false);
        mm.when(() -> ModelManager.isTimelineModel(Mockito.anyString())).thenReturn(false);
        stubField("id", "id", FieldType.LONG);
        stubField("subject", "subject", FieldType.STRING);
        stubField("toAddresses", "to_addresses", FieldType.MULTI_STRING);
        stubField("body", "body", FieldType.TEXT);
        stubField("sentAt", "sent_at", FieldType.DATE_TIME);
    }

    @AfterEach
    void tearDown() {
        mm.close();
        ReflectionTestUtils.setField(DBUtil.class, "singleDbDialect", previousDialect);
    }

    @Test
    void anIdFallbackNeverRendersLikeOnTheBigint() {
        searchedBy("id");

        Built built = build(Operator.CONTAINS, "ct02@example.com");

        assertThat(built.where()).doesNotContain("ILIKE").contains("1 = 0");
        assertThat(built.args()).isEmpty();
    }

    @Test
    void aWholeNumberFindsTheRecordWithThatId() {
        searchedBy("id");

        Built built = build(Operator.CONTAINS, " 901096357098536967 ");

        assertThat(built.where()).contains("t.id =").doesNotContain("ILIKE");
        assertThat(built.args()).containsExactly(901096357098536967L);
    }

    @Test
    void theNegationOfAnUnmatchableSearchKeepsEveryRow() {
        searchedBy("id");

        assertThat(build(Operator.NOT_CONTAINS, "abc").where()).contains("1 = 1");
        assertThat(build(Operator.NOT_CONTAINS, "42").where()).contains("t.id !=").doesNotContain("ILIKE");
    }

    @Test
    void textMembersAreStillSearchedAsWritten() {
        searchedBy("toAddresses", "subject");

        Built built = build(Operator.CONTAINS, "ct02@example.com");

        assertThat(built.where()).contains("t.to_addresses ILIKE").contains("t.subject ILIKE").contains(" OR ");
        assertThat(built.args()).containsExactly("%ct02@example.com%", "%ct02@example.com%");
    }

    /** An unbounded body is free text too: searched as written, like the String beside it. */
    @Test
    void aTextMemberIsSearchedAsWritten() {
        searchedBy("subject", "body");

        Built built = build(Operator.CONTAINS, "overtime");

        assertThat(built.where()).contains("t.subject ILIKE").contains("t.body ILIKE").contains(" OR ");
        assertThat(built.args()).containsExactly("%overtime%", "%overtime%");
    }

    /** A text member and a non-text one in one box: the text one still matches, the other cannot fail. */
    @Test
    void aMixedSearchKeepsTheTextMatchAndDefusesTheRest() {
        searchedBy("subject", "sentAt");

        Built built = build(Operator.CONTAINS, "Welcome");

        assertThat(built.where()).contains("t.subject ILIKE").contains("1 = 0").doesNotContain("t.sent_at");
        assertThat(built.args()).containsExactly("%Welcome%");
    }

    private void searchedBy(String... fields) {
        MetaModel model = new MetaModel();
        ReflectionTestUtils.setField(model, "modelName", MODEL);
        ReflectionTestUtils.setField(model, "tableName", TABLE);
        ReflectionTestUtils.setField(model, "searchName", List.of(fields));
        mm.when(() -> ModelManager.getModel(MODEL)).thenReturn(model);
    }

    private void stubField(String fieldName, String columnName, FieldType fieldType) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", MODEL);
        ReflectionTestUtils.setField(field, "fieldName", fieldName);
        ReflectionTestUtils.setField(field, "columnName", columnName);
        ReflectionTestUtils.setField(field, "fieldType", fieldType);
        mm.when(() -> ModelManager.existField(MODEL, fieldName)).thenReturn(true);
        mm.when(() -> ModelManager.getModelField(MODEL, fieldName)).thenReturn(field);
    }

    private static Built build(Operator operator, Object value) {
        SqlWrapper sqlWrapper = new SqlWrapper(MODEL);
        FlexQuery flexQuery = new FlexQuery(Filters.of("searchName", operator, value));
        new WhereBuilder(sqlWrapper, flexQuery).build();
        Object where = ReflectionTestUtils.getField(sqlWrapper, "whereClause");
        Object sqlParams = ReflectionTestUtils.getField(sqlWrapper, "sqlParams");
        @SuppressWarnings("unchecked")
        List<Object> args = (List<Object>) ReflectionTestUtils.getField(sqlParams, "args");
        return new Built(where == null ? "" : where.toString(), new ArrayList<>(args));
    }

    private record Built(String where, List<Object> args) {}
}
