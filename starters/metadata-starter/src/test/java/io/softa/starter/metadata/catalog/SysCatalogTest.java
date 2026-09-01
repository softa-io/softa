package io.softa.starter.metadata.catalog;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import io.softa.starter.metadata.entity.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the reflected persistence descriptor (the single source of truth). The
 * {@code data} column set is exactly what {@code DiffEngine} compares,
 * {@code SysJdbcWriter} writes and {@code SysJdbcLoader} reads — so these
 * assertions guard against silent drift (notably the historical {@code hidden}
 * omission) and against accidentally managing structural / runtime columns.
 */
class SysCatalogTest {

    private static Set<String> dataCols(Class<?> type) {
        return Set.copyOf(SysCatalog.of(type).data().stream().map(SysColumn::column).toList());
    }

    private static List<String> keyCols(Class<?> type) {
        return SysCatalog.of(type).keys().stream().map(SysColumn::column).toList();
    }

    @Test
    void sysField_includesHidden_excludesStructuralAndState() {
        Set<String> data = dataCols(SysField.class);
        // The historical bug guarded here: `hidden` was written + loaded but
        // omitted from equality. The descriptor now includes it for all three.
        assertTrue(data.contains("hidden"), "hidden must be a managed data column");
        assertTrue(data.contains("field_type"));
        assertTrue(data.contains("widget_type"));
        // Structural / surrogate / ownership columns must never be data.
        assertFalse(data.contains("id"));
        assertFalse(data.contains("app_code"));
        assertFalse(data.contains("model_id"));
        assertFalse(data.contains("ownership"));

        assertEquals(Set.of("model_name", "field_name"), Set.copyOf(keyCols(SysField.class)));
        assertEquals("sys_field", SysCatalog.of(SysField.class).table());
        assertNotNull(SysCatalog.of(SysField.class).appCodeColumn(), "app_code handled structurally");
    }

    @Test
    void sysOptionItem_excludesActiveRuntimeState() {
        Set<String> data = dataCols(SysOptionItem.class);
        assertFalse(data.contains("active"), "runtime active-control state is not scanner-managed");
        assertTrue(data.contains("sequence"));
        assertTrue(data.contains("item_tone"));
        assertEquals(Set.of("option_set_code", "item_code"), Set.copyOf(keyCols(SysOptionItem.class)));
    }

    @Test
    void sysOptionSet_excludesDeletedRuntimeState() {
        Set<String> data = dataCols(SysOptionSet.class);
        assertEquals(Set.of("label", "description"), data);
        assertFalse(data.contains("deleted"));
        assertEquals(List.of("option_set_code"), keyCols(SysOptionSet.class));
    }

    @Test
    void sysModel_tableKeysAndDataColumns() {
        assertEquals("sys_model", SysCatalog.of(SysModel.class).table());
        assertEquals(List.of("model_name"), keyCols(SysModel.class));
        Set<String> data = dataCols(SysModel.class);
        assertTrue(data.contains("default_order"));
        // The `business_key` *field* (List<String> column) is data; it is NOT
        // the @Model.businessKey *key* ({modelName}). Distinct concerns.
        assertTrue(data.contains("business_key"));
        assertFalse(data.contains("model_fields"), "in-memory relation has no @Field");
    }

    @Test
    void sysModelIndex_keysAndFk() {
        assertEquals(Set.of("model_name", "index_name"), Set.copyOf(keyCols(SysModelIndex.class)));
        assertEquals(Set.of("index_fields", "unique_index", "index_type", "message"),
                dataCols(SysModelIndex.class));
    }
}
