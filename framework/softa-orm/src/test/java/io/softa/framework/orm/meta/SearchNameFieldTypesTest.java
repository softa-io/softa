package io.softa.framework.orm.meta;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.enums.FieldType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which fields a model may declare in {@code @Model(searchName)}.
 *
 * <p>MultiString joins String: it is stored as delimited text, so the search box's CONTAINS is a
 * substring match on it exactly as on a String. The mail send log needs it — what someone looking
 * into "this user never got the email" has in hand is the recipient, and recipients are a list.
 *
 * <p>Text joins them too: it is a String without a width, and the query already searched it as text.
 * Refusing it here stopped the application at boot once {@code InboxNotification.content} — searched
 * by the inbox — was widened to Text (softa 3.0.1).
 */
class SearchNameFieldTypesTest {

    private static final String MODEL = "MailLog";

    @BeforeAll
    static void ensureSystemConfig() {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    @Test
    void aMultiStringFieldMayBeSearchedBy() {
        MetaModel model = modelSearchedBy("toAddresses", "subject");

        validate(model, FieldType.MULTI_STRING, FieldType.STRING);

        assertThat(model.getSearchName()).containsExactly("toAddresses", "subject");
    }

    @Test
    void aTextFieldMayBeSearchedBy() {
        MetaModel model = modelSearchedBy("title", "content");

        validate(model, FieldType.STRING, FieldType.TEXT);

        assertThat(model.getSearchName()).containsExactly("title", "content");
    }

    @Test
    void aNonTextFieldIsStillRefusedAtBoot() {
        MetaModel model = modelSearchedBy("sentAt");

        assertThatThrownBy(() -> validate(model, FieldType.DATE_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("String, Text and MultiString");
    }

    private static MetaModel modelSearchedBy(String... fields) {
        MetaModel model = new MetaModel();
        ReflectionTestUtils.setField(model, "modelName", MODEL);
        ReflectionTestUtils.setField(model, "searchName", List.of(fields));
        return model;
    }

    private static void validate(MetaModel model, FieldType... types) {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class, Answers.CALLS_REAL_METHODS)) {
            List<String> fields = model.getSearchName();
            for (int i = 0; i < fields.size(); i++) {
                MetaField field = new MetaField();
                field.setModelName(MODEL);
                field.setFieldName(fields.get(i));
                field.setFieldType(types[i]);
                mm.when(() -> ModelManager.getModelField(MODEL, field.getFieldName())).thenReturn(field);
            }
            ReflectionTestUtils.invokeMethod(ModelManager.class, "validateSearchName", model);
        }
    }
}
