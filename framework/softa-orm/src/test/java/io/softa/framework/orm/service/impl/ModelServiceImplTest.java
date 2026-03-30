package io.softa.framework.orm.service.impl;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

class ModelServiceImplTest {

    @Test
    void createOrUpdateUsesTupleFilterToSplitRows() {
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.getModelField("SysField", "modelName"))
                    .thenReturn(createMetaField("SysField", "modelName", FieldType.STRING));
            modelManager.when(() -> ModelManager.getModelField("SysField", "fieldName"))
                    .thenReturn(createMetaField("SysField", "fieldName", FieldType.STRING));

            ModelServiceImpl<Long> modelService = Mockito.spy(new ModelServiceImpl<>());
            doReturn(List.of(new HashMap<>(Map.of(
                    ModelConstant.ID, 10L,
                    "modelName", "User",
                    "fieldName", "name"
            )))).when(modelService).searchList(eq("SysField"), any(FlexQuery.class));
            doReturn(Boolean.TRUE).when(modelService).updateList(eq("SysField"), anyList());
            doReturn(List.of(20L)).when(modelService).createList(eq("SysField"), anyList());

            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(new HashMap<>(Map.of("modelName", "User", "fieldName", "name", "labelName", "Name")));
            rows.add(new HashMap<>(Map.of("modelName", "Order", "fieldName", "status", "labelName", "Status")));

            modelService.createOrUpdate("SysField", rows, List.of("modelName", "fieldName"));

            ArgumentCaptor<FlexQuery> flexQueryCaptor = ArgumentCaptor.forClass(FlexQuery.class);
            verify(modelService).searchList(eq("SysField"), flexQueryCaptor.capture());
            Assertions.assertEquals(
                    "[\"modelName,fieldName\",\"IN\",[[\"User\",\"name\"],[\"Order\",\"status\"]]]",
                    flexQueryCaptor.getValue().getFilters().toString()
            );

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Map<String, Object>>> updateCaptor = ArgumentCaptor.forClass(List.class);
            verify(modelService).updateList(eq("SysField"), updateCaptor.capture());
            Assertions.assertEquals(1, updateCaptor.getValue().size());
            Assertions.assertEquals(10L, updateCaptor.getValue().getFirst().get(ModelConstant.ID));
            Assertions.assertEquals("User", updateCaptor.getValue().getFirst().get("modelName"));
            Assertions.assertEquals("name", updateCaptor.getValue().getFirst().get("fieldName"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Map<String, Object>>> createCaptor = ArgumentCaptor.forClass(List.class);
            verify(modelService).createList(eq("SysField"), createCaptor.capture());
            Assertions.assertEquals(1, createCaptor.getValue().size());
            Assertions.assertFalse(createCaptor.getValue().getFirst().containsKey(ModelConstant.ID));
            Assertions.assertEquals("Order", createCaptor.getValue().getFirst().get("modelName"));
            Assertions.assertEquals("status", createCaptor.getValue().getFirst().get("fieldName"));
        }
    }

    private MetaField createMetaField(String modelName, String fieldName, FieldType fieldType) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", modelName);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "fieldType", fieldType);
        return metaField;
    }
}
