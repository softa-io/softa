package io.softa.starter.file.excel.imports.handler;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.OptionManager;
import io.softa.starter.file.dto.ImportFieldDTO;

/**
 * MultiOptionHandler
 * Compatible with the label and itemCode of OptionItem.
 */
public class MultiOptionHandler extends BaseImportHandler {

    public MultiOptionHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        super(metaField, importFieldDTO);
    }

    /**
     * Handle the MultiOption value
     * @param value The MultiOption value
     * @return The MultiOption items
     */
    public Object handleValue(Object value) {
        if (value instanceof String multiOptionStr && StringUtils.isNotBlank(multiOptionStr)) {
            String optionSetCode = metaField.getOptionSetCode();
            String[] optionList = StringUtils.split(multiOptionStr.trim(), ",");
            List<String> codeList = new ArrayList<>();
            for (String optionStr : optionList) {
                if (OptionManager.existsItemCode(optionSetCode, optionStr)) {
                    codeList.add(optionStr);
                } else {
                    // Treat the option string as label
                    String optionItemCode = OptionManager.getItemCodeByLabel(optionSetCode, optionStr);
                    if (optionItemCode == null) {
                        throw new ValidationException("The multi-option field `{0}` does not exist item `{1}`",
                                label, optionStr);
                    }
                    codeList.add(optionItemCode);
                }
            }
            return codeList;
        } else {
            return value;
        }
    }

}
