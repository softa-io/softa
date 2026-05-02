package io.softa.framework.orm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Option reference object.
 * Used to reference the option item code, name, tone, and icon.
 */
@Data
@Schema(name = "OptionReference")
public class OptionReference implements Serializable  {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Option Item Code")
    private String itemCode;

    @Schema(description = "Option Item Name")
    private String itemName;

    @Schema(description = "Option Item Tone")
    private String itemTone;

    @Schema(description = "Option Item Icon")
    private String itemIcon;

    /**
     * Create an OptionReference object.
     *
     * @param itemCode Option item code
     * @param itemName Option item name
     * @return OptionReference object
     */
    static public OptionReference of(String itemCode, String itemName) {
        OptionReference optionReference = new OptionReference();
        optionReference.setItemCode(itemCode);
        optionReference.setItemName(itemName);
        return optionReference;
    }

    /**
     * Create an OptionReference object.
     *
     * @param itemCode Option item code
     * @param itemName Option item name
     * @param itemTone Option item tone
     * @param itemIcon Option item icon
     * @return OptionReference object
     */
    static public OptionReference of(String itemCode, String itemName, String itemTone, String itemIcon) {
        OptionReference optionReference = OptionReference.of(itemCode, itemName);
        optionReference.setItemTone(itemTone);
        optionReference.setItemIcon(itemIcon);
        return optionReference;
    }
}
