package io.softa.starter.file.excel.imports.handler;

import java.math.BigDecimal;

import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.starter.file.dto.ImportFieldDTO;

/**
 * Converts a numeric column's cell text, and reports a bad one by column name.
 *
 * <p>Numeric columns used to have no handler at all: the raw text travelled to the write and the ORM's
 * own conversion threw, so the row's {@code Failed Reason} was the JDK's bare
 * {@code For input string: "Branch / Branch"} — no column, no expected format, and for a batch of
 * columns no way to tell which one the user has to fix. Converting here is what buys the column name.
 *
 * <p>It also means the row map holds real numbers by the time it reaches the write, the same way the
 * date and option handlers normalize their columns.
 */
public class NumberHandler extends BaseImportHandler {

    public NumberHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        super(metaField, importFieldDTO);
    }

    /**
     * @param value the cell value; a non-String (Excel numeric cell) is already a number and passes
     *              through untouched
     */
    @Override
    public Object handleValue(Object value) {
        if (!(value instanceof String valueStr) || StringUtils.isBlank(valueStr)) {
            return value;
        }
        String trimmed = valueStr.trim();
        FieldType fieldType = metaField.getFieldType();
        try {
            return switch (fieldType) {
                case INTEGER -> Integer.valueOf(trimmed);
                case LONG -> Long.valueOf(trimmed);
                case DOUBLE -> Double.valueOf(trimmed);
                case BIG_DECIMAL -> new BigDecimal(trimmed);
                default -> value;
            };
        } catch (NumberFormatException e) {
            // Worded for whoever fills the sheet: the column, and the shape it takes. The Java type name
            // said nothing to them, and the usual cause — a thousands separator copied from a report —
            // went unexplained.
            boolean whole = fieldType == FieldType.INTEGER || fieldType == FieldType.LONG;
            throw new ValidationException(whole
                    ? "{0}: `{1}` is not a whole number. Enter digits only, without thousands separators (e.g. 4500)."
                    : "{0}: `{1}` is not a number. Enter digits only, without thousands separators (e.g. 4500.50).",
                    label, trimmed);
        }
    }
}
