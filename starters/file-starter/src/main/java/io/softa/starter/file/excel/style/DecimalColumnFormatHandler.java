package io.softa.starter.file.excel.style;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.data.DataFormatData;
import org.apache.fesod.sheet.metadata.data.WriteCellData;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

/**
 * Gives an exported decimal column the number format its field's scale asks for, so that 650.00 reads
 * as 650.00 and not as 650.
 *
 * <p>A decimal is written as a number, which is right: a reader can sum it. But a number cell with no
 * format is {@code General}, and General drops trailing zeros — one exported column shows 650, 427.4
 * and 476.76 side by side for values that are all two-decimal amounts on screen, which is how an
 * amount column was reported as "not two decimals". The scale a field declares, or the framework's
 * default when it declares none, is the number of decimals the value is kept to, so the column is
 * formatted to exactly that many, with digit grouping as the screen shows.
 *
 * <p>A cell handler rather than a column default: every exported cell already carries an explicit
 * style from {@link CommonFontStyleHandler}, and an explicit style beats the column's default, which
 * is why {@link TemporalColumnFormatHandler} serves import templates only. The format goes onto the
 * cell's own {@code WriteCellStyle}; the writer's style filling runs after this handler and keeps one
 * workbook style per distinct cell style, so a column of a thousand amounts costs one style.
 *
 * <p>Number cells only. A decimal retyped as text, or an empty cell, is left as it is.
 */
public class DecimalColumnFormatHandler implements CellWriteHandler {

    /** Column index → the Excel number format its field's scale asks for. */
    private final Map<Integer, String> formatByColumn;

    private DecimalColumnFormatHandler(Map<Integer, String> formatByColumn) {
        this.formatByColumn = formatByColumn;
    }

    /**
     * A handler for the decimal columns among {@code fieldNames}, or null when there are none — the
     * caller passes that straight to the sheet builder, which ignores nulls.
     *
     * <p>{@code fieldNames} is positional: index i names what column i holds, dotted paths included
     * ({@code payGroupId.budget}), resolved the same way the exporter resolves its headers. A name
     * that resolves to nothing simply has no format to apply — a template may carry columns that are
     * not fields at all, and a pivot appends columns after the fields.
     */
    public static DecimalColumnFormatHandler forFields(String modelName, List<String> fieldNames) {
        if (modelName == null || fieldNames == null) {
            return null;
        }
        Map<Integer, String> formats = new LinkedHashMap<>();
        for (int i = 0; i < fieldNames.size(); i++) {
            String format = formatOf(modelName, fieldNames.get(i));
            if (format != null) {
                formats.put(i, format);
            }
        }
        return formats.isEmpty() ? null : new DecimalColumnFormatHandler(formats);
    }

    private static String formatOf(String modelName, String fieldName) {
        MetaField metaField;
        try {
            metaField = ModelManager.getLastFieldOfCascaded(modelName, fieldName);
        } catch (RuntimeException e) {
            // Not a field of this model — nothing to format, and not a reason to fail the download.
            return null;
        }
        if (metaField == null || metaField.getFieldType() == null) {
            return null;
        }
        return switch (metaField.getFieldType()) {
            case BIG_DECIMAL, DOUBLE -> formatOfScale(metaField.getScale());
            default -> null;
        };
    }

    /**
     * The format for a given number of decimals: {@code #,##0.00} for 2, {@code #,##0} for 0. A field
     * that declares no scale is kept to the framework's default, so it is formatted to that.
     */
    private static String formatOfScale(Integer scale) {
        int decimals = scale == null || scale < 0 ? BaseConstant.DEFAULT_SCALE : scale;
        return decimals == 0 ? "#,##0" : "#,##0." + "0".repeat(decimals);
    }

    @Override
    public void afterCellDispose(CellWriteHandlerContext context) {
        if (Boolean.TRUE.equals(context.getHead())) {
            return;
        }
        String format = formatByColumn.get(context.getColumnIndex());
        if (format == null) {
            return;
        }
        WriteCellData<?> cellData = context.getFirstCellData();
        if (cellData == null || cellData.getType() != CellDataTypeEnum.NUMBER) {
            return;
        }
        DataFormatData dataFormatData = new DataFormatData();
        dataFormatData.setFormat(format);
        cellData.getOrCreateStyle().setDataFormatData(dataFormatData);
    }
}
