package io.softa.framework.orm.meta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.jdbc.JdbcService;

/**
 * Global option set cache manager
 */
@Component
@Slf4j
public class OptionManager {

    private static volatile Map<String, Map<String, MetaOptionItem>> META_OPTION_SET_MAP = new ConcurrentHashMap<>(100);
    private static final ThreadLocal<Map<String, Map<String, MetaOptionItem>>> BUILDING_OPTION_SET_MAP = new ThreadLocal<>();

    private final ReentrantLock initLock = new ReentrantLock();

    @Autowired
    private JdbcService<?> jdbcService;

    private static Map<String, Map<String, MetaOptionItem>> currentOptionSetMap() {
        Map<String, Map<String, MetaOptionItem>> buildingOptionSetMap = BUILDING_OPTION_SET_MAP.get();
        return buildingOptionSetMap != null ? buildingOptionSetMap : META_OPTION_SET_MAP;
    }

    private static Map<String, Map<String, MetaOptionItem>> createMutableOptionSetMap() {
        return new HashMap<>(100);
    }

    private static Map<String, Map<String, MetaOptionItem>> freezeOptionSetMap(
            Map<String, Map<String, MetaOptionItem>> draft) {
        Map<String, Map<String, MetaOptionItem>> frozenOptionSetMap =
                new ConcurrentHashMap<>(Math.max(100, draft.size()));
        draft.forEach((optionSetCode, optionItems) ->
                frozenOptionSetMap.put(optionSetCode, Collections.unmodifiableMap(new LinkedHashMap<>(optionItems))));
        return frozenOptionSetMap;
    }

    private static Map<String, MetaOptionItem> getOptionItems(String optionSetCode) {
        Map<String, MetaOptionItem> optionItems = currentOptionSetMap().get(optionSetCode);
        Assert.isTrue(optionItems != null,
                "optionSetCode {0} does not exist in OptionSet metadata.", optionSetCode);
        return optionItems;
    }

    /**
     * Initialize the optionSet structure as: {optionSetCode: {itemCode: metaOptionItem}}
     */
    public void init() {
        initLock.lock();
        try {
            Map<String, Map<String, MetaOptionItem>> draft = createMutableOptionSetMap();
            BUILDING_OPTION_SET_MAP.set(draft);
            // Select all optionItems from the database, and order by optionItem sequence
            List<MetaOptionItem> metaOptionItems =
                    jdbcService.selectMetaEntityList("SysOptionItem", MetaOptionItem.class, "sequence");
            metaOptionItems.stream()
                    .filter(item -> StringUtils.isNotBlank(item.getOptionSetCode()))
                    .forEach(item -> draft.computeIfAbsent(item.getOptionSetCode(), key -> new LinkedHashMap<>())
                            .put(item.getItemCode(), item));
            META_OPTION_SET_MAP = freezeOptionSetMap(draft);
        } finally {
            BUILDING_OPTION_SET_MAP.remove();
            initLock.unlock();
        }
    }

    /**
     * Get the optionItems by optionSetCode
     *
     * @param optionSetCode optionSet code
     * @return unmodifiable optionItems
     */
    public static List<MetaOptionItem> getMetaOptionItems(String optionSetCode) {
        return getOptionItems(optionSetCode).values().stream().toList();
    }

    /**
     * Get the optionItem object by optionSetCode and optionItemCode, return null if not exists.
     *
     * @param optionSetCode optionSet code
     * @param itemCode option item code
     * @return optionItem object
     */
    public static MetaOptionItem getMetaOptionItem(String optionSetCode, String itemCode) {
        return getOptionItems(optionSetCode).get(itemCode);
    }

    /**
     * Get the optionItem label by optionSetCode and optionItemCode, return null if not exists.
     *
     * @param optionSetCode optionSet code
     * @param itemCode option item code
     * @return optionItem label
     */
    public static String getLabelByCode(String optionSetCode, String itemCode) {
        MetaOptionItem metaOptionItem = getMetaOptionItem(optionSetCode, itemCode);
        return metaOptionItem == null ? null : metaOptionItem.getLabel();
    }

    /**
     * Get the optionItem code by optionSetCode and optionItem label, return null if not exists.
     *
     * @param optionSetCode optionSet code
     * @param label option item label
     * @return optionItem code
     */
    public static String getItemCodeByLabel(String optionSetCode, String label) {
        for (MetaOptionItem metaOptionItem : getOptionItems(optionSetCode).values()) {
            if (metaOptionItem.getLabel().equals(label)) {
                return metaOptionItem.getItemCode();
            }
        }
        return null;
    }

    /**
     * Check if the optionSet exists by optionSetCode.
     *
     * @param optionSetCode optionSet code
     * @return true if exists
     */
    public static boolean existsOptionSetCode(String optionSetCode) {
        return currentOptionSetMap().containsKey(optionSetCode);
    }

    /**
     * Check if the optionItem exists by optionSetCode and optionItemCode.
     *
     * @param optionSetCode optionSet code
     * @param itemCode option item code
     * @return true if exists
     */
    public static boolean existsItemCode(String optionSetCode, String itemCode) {
        return currentOptionSetMap().containsKey(optionSetCode)
                && currentOptionSetMap().get(optionSetCode).containsKey(itemCode);
    }
}
