package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.utils.ReflectTool;
import io.softa.framework.orm.vo.ModelReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A child model with a dynamic cascaded field through the back reference its parent's OneToMany expands by.
 *
 * <p>The expansion reads the children with the back reference kept as a raw id and groups them by it. The
 * cascaded field brought in a group processor on that same field, which turned the reference into a
 * {@link ModelReference}: no child matched its parent, and the expansion came back empty without a word —
 * whenever the read included the cascaded field, as asking for every field does.
 */
class OneToManyBackReferenceCascadeTest {

    private static final String CHILD = "OrderLine";
    private static final String PARENT = "SalesOrder";
    private static final String BACK_REFERENCE = "orderId";
    private static final long PARENT_ID = 7L;

    private static MetaField backReference() {
        MetaField field = mock(MetaField.class);
        when(field.getModelName()).thenReturn(CHILD);
        when(field.getFieldName()).thenReturn(BACK_REFERENCE);
        when(field.getRelatedModel()).thenReturn(PARENT);
        when(field.getRelatedField()).thenReturn("id");
        return field;
    }

    private static MetaField cascadedThroughIt() {
        MetaField field = mock(MetaField.class);
        when(field.getFieldName()).thenReturn("orderCode");
        when(field.getDependentFields()).thenReturn(List.of(BACK_REFERENCE, "code"));
        return field;
    }

    private static Map<String, Object> processedRow(FlexQuery query) {
        MetaField backReference = backReference();
        MetaModel parentModel = mock(MetaModel.class);
        Map<String, Object> parentRow = new HashMap<>(Map.of("id", PARENT_ID, "code", "SO-1"));
        Map<String, Object> childRow = new HashMap<>(Map.of("id", 70L, BACK_REFERENCE, PARENT_ID));
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
             MockedStatic<ReflectTool> reflect = mockStatic(ReflectTool.class);
             MockedStatic<IdUtils> ids = mockStatic(IdUtils.class, CALLS_REAL_METHODS)) {
            models.when(() -> ModelManager.getModelDisplayName(PARENT)).thenReturn(List.of("code"));
            models.when(() -> ModelManager.getModel(PARENT)).thenReturn(parentModel);
            models.when(() -> ModelManager.getModelField(CHILD, BACK_REFERENCE)).thenReturn(backReference);
            ids.when(() -> IdUtils.formatId(eq(PARENT), any())).thenAnswer(call -> call.getArgument(1));
            reflect.when(() -> ReflectTool.searchListIgnoringRowScope(eq(PARENT), any(FlexQuery.class)))
                    .thenReturn(List.of(parentRow));

            XToOneGroupProcessor processor = new XToOneGroupProcessor(backReference, AccessType.READ, query);
            processor.addCascadedField(cascadedThroughIt());
            processor.batchProcessOutputRows(new ArrayList<>(List.of(childRow)));
        }
        return childRow;
    }

    private static FlexQuery query() {
        FlexQuery query = new FlexQuery();
        query.setConvertType(ConvertType.REFERENCE);
        return query;
    }

    @Test
    void theBackReferenceAnExpansionGroupsByStaysARawId() {
        FlexQuery query = query();
        query.setKeepIdField(BACK_REFERENCE);

        Map<String, Object> row = processedRow(query);

        assertThat(row.get(BACK_REFERENCE)).isEqualTo(PARENT_ID);
        assertThat(row.get("orderCode")).isEqualTo("SO-1");
    }

    @Test
    void anOrdinaryReadStillExpandsTheReference() {
        Map<String, Object> row = processedRow(query());

        assertThat(row.get(BACK_REFERENCE)).isInstanceOf(ModelReference.class);
        assertThat(row.get("orderCode")).isEqualTo("SO-1");
    }
}
