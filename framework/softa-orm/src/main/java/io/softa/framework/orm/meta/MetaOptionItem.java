package io.softa.framework.orm.meta;

import java.io.Serial;
import java.io.Serializable;
import lombok.*;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.context.ContextHolder;

/**
 * MetaOptionItem
 */
@Getter
@Setter(AccessLevel.PACKAGE)
@ToString
@EqualsAndHashCode
public class MetaOptionItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long appId;

    private Long optionSetId;

    private String optionSetCode;

    private Integer sequence;

    private String itemCode;

    private String itemName;

    private String parentItemCode;

    private String itemTone;

    private String itemIcon;

    private String description;

    /**
     * Get the item name by language code from translations.
     * If the translation is not found, return the item name.
     *
     * @return item name
     */
    public String getItemName() {
        String languageCode = ContextHolder.getContext().getLanguage().getCode();
        MetaOptionItemTrans itemTrans = TranslationCache.getOptionItemTrans(languageCode, id);
        if (itemTrans == null) {
            return itemName;
        } else {
            String translation = itemTrans.getItemName();
            return StringUtils.isNotBlank(translation) ? translation : itemName;
        }
    }
}