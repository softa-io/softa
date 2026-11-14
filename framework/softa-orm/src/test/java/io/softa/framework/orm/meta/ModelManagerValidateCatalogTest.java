package io.softa.framework.orm.meta;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.enums.FieldType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ModelManager#validateCatalog}: the boot checks over a catalog built in memory, which is
 * checked and then thrown away — the metadata the application reads is never replaced by it.
 */
class ModelManagerValidateCatalogTest {

    @BeforeAll
    static void ensureSystemConfig() {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();
        }
    }

    /** A relation onto a model the catalog does not hold: a mistake only the whole catalog shows. */
    @Test
    void aCatalogTheBootWouldRefuseFailsTheCheck() {
        assertThatThrownBy(() -> ModelManager.validateCatalog(List.of(model("Ticket")),
                List.of(field("Ticket", "id", FieldType.LONG), assignee()), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relatedModel `Agent` does not exist");
    }

    @Test
    void aSoundCatalogPassesWithoutBeingLoaded() {
        ModelManager.validateCatalog(List.of(model("Ticket"), model("Agent")),
                List.of(field("Ticket", "id", FieldType.LONG), assignee(), field("Agent", "id", FieldType.LONG)),
                List.of());

        assertThat(ModelManager.existModel("Ticket")).isFalse();
        assertThat(ModelManager.existModel("Agent")).isFalse();
    }

    private static MetaField assignee() {
        MetaField assignee = field("Ticket", "assigneeId", FieldType.MANY_TO_ONE);
        assignee.setRelatedModel("Agent");
        return assignee;
    }

    private static MetaModel model(String modelName) {
        MetaModel model = new MetaModel();
        model.setModelName(modelName);
        model.setLabel(modelName);
        model.setTableName(StringTools.toUnderscoreCase(modelName));
        return model;
    }

    private static MetaField field(String modelName, String fieldName, FieldType fieldType) {
        MetaField field = new MetaField();
        field.setModelName(modelName);
        field.setFieldName(fieldName);
        field.setLabel(fieldName);
        field.setColumnName(StringTools.toUnderscoreCase(fieldName));
        field.setFieldType(fieldType);
        return field;
    }
}
