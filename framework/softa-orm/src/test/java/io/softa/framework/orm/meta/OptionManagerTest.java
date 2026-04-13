package io.softa.framework.orm.meta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.jdbc.JdbcService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

class OptionManagerTest {

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(OptionManager.class, "META_OPTION_SET_MAP", new ConcurrentHashMap<>(100));
    }

    @Test
    void initBuildsOrderedSnapshot() {
        OptionManager optionManager = new OptionManager();
        JdbcService<?> jdbcService = Mockito.mock(JdbcService.class);
        ReflectionTestUtils.setField(optionManager, "jdbcService", jdbcService);
        doReturn(List.of(
                optionItem("status_set", "OPEN", "Open"),
                optionItem("status_set", "PENDING", "Pending"),
                optionItem("color_set", "RED", "Red")))
                .when(jdbcService).selectMetaEntityList("SysOptionItem", MetaOptionItem.class, "sequence");

        optionManager.init();

        assertEquals(List.of("OPEN", "PENDING"),
                OptionManager.getMetaOptionItems("status_set").stream().map(MetaOptionItem::getItemCode).toList());
        assertTrue(OptionManager.existsItemCode("color_set", "RED"));
        assertEquals("PENDING", OptionManager.getMetaOptionItem("status_set", "PENDING").getItemCode());
    }

    @Test
    void initFailureKeepsPreviousSnapshot() {
        Map<String, Map<String, MetaOptionItem>> previousSnapshot = new ConcurrentHashMap<>();
        Map<String, MetaOptionItem> legacyItems = new LinkedHashMap<>();
        legacyItems.put("LEGACY", optionItem("legacy_set", "LEGACY", "Legacy"));
        previousSnapshot.put("legacy_set", legacyItems);
        ReflectionTestUtils.setField(OptionManager.class, "META_OPTION_SET_MAP", previousSnapshot);

        OptionManager optionManager = new OptionManager();
        JdbcService<?> jdbcService = Mockito.mock(JdbcService.class);
        ReflectionTestUtils.setField(optionManager, "jdbcService", jdbcService);
        doThrow(new RuntimeException("load failed"))
                .when(jdbcService).selectMetaEntityList("SysOptionItem", MetaOptionItem.class, "sequence");

        assertThrows(RuntimeException.class, optionManager::init);
        assertEquals(List.of("LEGACY"),
                OptionManager.getMetaOptionItems("legacy_set").stream().map(MetaOptionItem::getItemCode).toList());
    }

    private MetaOptionItem optionItem(String optionSetCode, String itemCode, String itemName) {
        MetaOptionItem metaOptionItem = new MetaOptionItem();
        ReflectionTestUtils.setField(metaOptionItem, "optionSetCode", optionSetCode);
        ReflectionTestUtils.setField(metaOptionItem, "itemCode", itemCode);
        ReflectionTestUtils.setField(metaOptionItem, "itemName", itemName);
        return metaOptionItem;
    }
}
