package io.softa.starter.file.excel.export.support;

import java.math.BigDecimal;

import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.data.WriteCellData;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;

/**
 * Writes a whole number Excel cannot hold exactly as text, so its digits survive the file.
 *
 * <p>A spreadsheet number is a double, and Excel keeps 15 significant digits of one. Our ids are
 * 18-19 digit snowflakes, so writing one as a number is not a display problem to be fixed with a
 * format — the value is already gone by the time the bytes are written. {@code 854278123456789012}
 * comes back out of the file as {@code 854278123456788992}, which is what the exported ID column was
 * reported as: {@code 8.54278E+17} on screen, a different id underneath, and useless for the
 * re-import and reconciliation the column exists for.
 *
 * <p>So the cell is retyped rather than restyled. {@code WriteCellData} still holds the value as an
 * exact {@link BigDecimal} at this point — the loss happens further down, in POI — which is why this
 * hooks the conversion rather than the written cell.
 *
 * <p>Only whole numbers, and only past 15 significant digits. Everything a reader would sum, sort or
 * chart stays a number: a real quantity is nowhere near this magnitude, and a decimal one is a
 * measurement rather than an identifier, so it keeps Excel's arithmetic even where it would round.
 */
public class LargeIntegerAsTextHandler implements CellWriteHandler {

    /** The largest whole number Excel represents exactly: 15 significant digits, so 15 nines. */
    private static final BigDecimal MAX_EXACT = new BigDecimal("999999999999999");

    @Override
    public void afterCellDataConverted(CellWriteHandlerContext context) {
        if (Boolean.TRUE.equals(context.getHead())) {
            return;
        }
        WriteCellData<?> cellData = context.getFirstCellData();
        if (cellData == null || cellData.getType() != CellDataTypeEnum.NUMBER) {
            return;
        }
        BigDecimal number = cellData.getNumberValue();
        if (number == null || !exceedsExcelPrecision(number)) {
            return;
        }
        cellData.setType(CellDataTypeEnum.STRING);
        cellData.setStringValue(number.toPlainString());
        cellData.setNumberValue(null);
    }

    /**
     * Whether writing this as a number would change it.
     *
     * <p>Magnitude, not {@link BigDecimal#precision()}: {@code 1E+18} strips to a precision of 1
     * while still needing 19 digits, so counting significant digits would let round ids through —
     * the ones a reader is most likely to mistake for correct.
     */
    private static boolean exceedsExcelPrecision(BigDecimal number) {
        return number.stripTrailingZeros().scale() <= 0 && number.abs().compareTo(MAX_EXACT) > 0;
    }
}
