package io.softa.starter.metadata.service.impl;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataDtoMapperTest {

    private static MetaField field(Long id, String name) {
        MetaField field = Mockito.mock(MetaField.class);
        Mockito.when(field.getId()).thenReturn(id);
        Mockito.when(field.getFieldName()).thenReturn(name);
        Mockito.when(field.getModelName()).thenReturn("Customer");
        Mockito.when(field.getFieldType()).thenReturn(FieldType.STRING);
        return field;
    }

    @Test
    void listsTheFieldsInTheOrderTheyWereCatalogued() {
        MetaModel model = Mockito.mock(MetaModel.class);
        Mockito.when(model.getModelName()).thenReturn("Customer");
        // The registry hands them back in no particular order.
        List<MetaField> fields = List.of(
                field(30L, "email"), field(null, "draft"), field(10L, "code"), field(20L, "name"));
        try (MockedStatic<ModelManager> manager = Mockito.mockStatic(ModelManager.class)) {
            manager.when(() -> ModelManager.getModel("Customer")).thenReturn(model);
            manager.when(() -> ModelManager.getModelFields("Customer")).thenReturn(fields);

            assertThat(MetadataDtoMapper.toModelDTO("Customer").getModelFields().keySet())
                    .containsExactly("code", "name", "email", "draft");
        }
    }
}
