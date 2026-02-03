package io.softa.starter.file.excel.handler;

import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.OptionManager;
import io.softa.starter.file.dto.ImportFieldDTO;

/**
 * BooleanHandler
 * Compatible with the itemName and itemCode of OptionItem.
 */
public class BooleanHandler extends BaseImportHandler {

    public BooleanHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        super(metaField, importFieldDTO);
    }

    /**
     * Handle the Object value
     * @param value The Object value
     * @return The Boolean value
     */
    public Object handleValue(Object value) {
        if (value instanceof String valueStr && StringUtils.isNotBlank(valueStr)) {
            valueStr = valueStr.trim().toLowerCase();
            String optionSetCode = BaseConstant.BOOLEAN_OPTION_SET_CODE;
            if (OptionManager.existsItemCode(optionSetCode, valueStr)) {
                return Boolean.valueOf(valueStr);
            } else {
                // Treat the boolean string as itemName
                String optionItemCode = OptionManager.getItemCodeByName(optionSetCode, valueStr);
                if (optionItemCode == null) {
                    throw new ValidationException("The Boolean field `{0}` is incorrect `{1}`", labelName, valueStr);
                }
                return Boolean.valueOf(optionItemCode);
            }
        } else {
            return value;
        }
    }

}
