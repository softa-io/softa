package io.softa.starter.file.excel.handler;

import io.softa.framework.orm.meta.MetaField;
import io.softa.starter.file.dto.ImportFieldDTO;

/**
 * Default handler
 */
public class DefaultHandler extends BaseImportHandler {

    public DefaultHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        super(metaField, importFieldDTO);
    }

}
