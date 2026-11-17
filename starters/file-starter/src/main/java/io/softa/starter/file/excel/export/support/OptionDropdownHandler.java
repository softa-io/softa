package io.softa.starter.file.excel.export.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.apache.fesod.sheet.write.handler.SheetWriteHandler;
import org.apache.fesod.sheet.write.handler.context.SheetWriteHandlerContext;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.ss.util.CellRangeAddressList;

/**
 * Turns the option-backed columns of an import template into Excel dropdowns holding item codes.
 *
 * <p>Takes the values already resolved — a column index mapped to its allowed item codes — and does no
 * IO of its own. That is deliberate: this runs inside workbook serialisation, and reaching for a
 * database connection at that point ties a query to the lifetime of a file write.
 *
 * <p>Two ways to attach a list, chosen per column by its total length:
 *
 * <ul>
 *   <li><b>Inline list</b> — the values go straight into the validation formula. Excel caps that
 *       formula at 255 characters and POI enforces it by throwing, so a set that serialises longer
 *       cannot use this form. Most do fit: the option sets these columns point at run 30–50
 *       characters in total.
 *   <li><b>Range reference</b> — the values are written down a column of a hidden sheet and the
 *       formula points at that range. The formula is then a fixed-length address, so the 255-character
 *       cap stops applying however many options there are.
 * </ul>
 *
 * <p>Inline is preferred where it fits, because the hidden sheet has a cost of its own: a reader can
 * unhide or delete it, and a deleted sheet leaves every dropdown pointing at a range that no longer
 * exists. Paying that only for the columns that need it keeps the common template to the two sheets
 * it has today.
 *
 * <p>A column with no resolvable options is skipped rather than failing the download. Missing option
 * data is a reason to hand back a template without one dropdown; it is not a reason to hand back
 * nothing.
 *
 * <p>Only the workbook's first sheet is touched — the template's own, the one people type into. The
 * instruction sheet beside it is prose.
 */
@Slf4j
public class OptionDropdownHandler implements SheetWriteHandler {

    /** Excel's limit on a data-validation formula, which an inline list has to fit inside. */
    private static final int INLINE_FORMULA_LIMIT = 255;

    /**
     * Rows below the header the dropdown covers. Excel wants a bounded range, and a row past the end
     * gets neither the list nor the validation — so this is also where a very large paste stops being
     * checked. Set well above any import anyone hand-fills.
     */
    private static final int DROPDOWN_ROWS = 5000;

    /** The sheet these columns belong to: the template's first, which is the one people type into. */
    private static final int MAIN_SHEET_INDEX = 0;

    /** Name of the sheet holding the values that do not fit inline. */
    static final String OPTIONS_SHEET_NAME = "_options";

    /** Column index (0-based, on the main sheet) → the item codes allowed in it. */
    private final Map<Integer, List<String>> optionsByColumn;

    /** Last row (0-based; the header is row 0) the dropdowns cover. */
    private final int lastRow;

    public OptionDropdownHandler(Map<Integer, List<String>> optionsByColumn) {
        this(optionsByColumn, 0);
    }

    /**
     * Dropdowns over the {@code dataRows} rows written under the header, and {@link #DROPDOWN_ROWS} more.
     *
     * <p>A file exported for re-import carries its records there; with the blank template's range, a
     * record past row 5000 would get neither the list nor the check, and a row added under the last
     * record neither.
     */
    public OptionDropdownHandler(Map<Integer, List<String>> optionsByColumn, int dataRows) {
        this.optionsByColumn = optionsByColumn == null ? Map.of() : new LinkedHashMap<>(optionsByColumn);
        this.lastRow = Math.max(0, dataRows) + DROPDOWN_ROWS;
    }

    @Override
    public void afterSheetCreate(SheetWriteHandlerContext context) {
        Sheet sheet = context.getWriteSheetHolder() == null ? null : context.getWriteSheetHolder().getSheet();
        if (sheet != null) {
            attach(sheet);
        }
    }

    /**
     * Attaches the dropdowns to a sheet.
     *
     * <p>Separate from the callback so the decision it makes — inline list or hidden sheet — can be
     * exercised against a plain workbook. Driving it through a write pipeline instead would be testing
     * the Excel library.
     */
    protected void attach(Sheet sheet) {
        if (optionsByColumn.isEmpty()) {
            return;
        }
        Workbook workbook = sheet.getWorkbook();
        // Identified by position, not by name. Excel caps a sheet name at 31 characters and truncates
        // silently past it, and most template names are longer than that — matching on the name meant
        // no sheet ever matched and the whole feature did nothing, with no error to show for it.
        if (workbook.getSheetIndex(sheet) != MAIN_SHEET_INDEX) {
            return;
        }
        DataValidationHelper helper = sheet.getDataValidationHelper();
        Sheet optionsSheet = null;
        int nextOptionsColumn = 0;

        for (Map.Entry<Integer, List<String>> entry : optionsByColumn.entrySet()) {
            int columnIndex = entry.getKey();
            List<String> codes = entry.getValue();
            if (codes == null || codes.isEmpty()) {
                continue;
            }
            try {
                DataValidationConstraint constraint;
                if (fitsInline(codes)) {
                    constraint = helper.createExplicitListConstraint(codes.toArray(new String[0]));
                } else {
                    if (optionsSheet == null) {
                        optionsSheet = createHiddenOptionsSheet(workbook);
                    }
                    writeOptionsColumn(optionsSheet, nextOptionsColumn, codes);
                    constraint = helper.createFormulaListConstraint(
                            rangeReference(nextOptionsColumn, codes.size()));
                    nextOptionsColumn++;
                }
                DataValidation validation = helper.createValidation(constraint,
                        new CellRangeAddressList(1, lastRow, columnIndex, columnIndex));
                // Reject anything not on the list, and say why. Without this Excel accepts a typo
                // silently and the row only fails later, during the import itself.
                validation.setShowErrorBox(true);
                // Reads backwards, and has to. POI writes this straight through to the file's
                // showDropDown attribute, inverted: setSuppressDropDownArrow(true) produces
                // showDropDown="false". And Excel reads that attribute inverted from its own name —
                // showDropDown="true" is what it writes when you UNCHECK "In-cell dropdown". The two
                // inversions do not cancel: passing false here hides the arrow, which is how this
                // shipped once, with the validations all present and no way to see them. Verified
                // against the generated XML, not against POI's getter, which reports its own naming.
                validation.setSuppressDropDownArrow(true);
                sheet.addValidationData(validation);
            } catch (RuntimeException e) {
                // One column's dropdown is not worth the whole template. Logged rather than swallowed
                // so "why is this column missing its dropdown" is answerable without a debugger.
                log.warn("Skipped the dropdown on column {} of sheet '{}': {}",
                        columnIndex, sheet.getSheetName(), e.getMessage());
            }
        }
    }

    /**
     * Whether the values can go into the validation formula itself rather than onto the hidden sheet.
     *
     * <p>Two things disqualify them. The obvious one is length, which Excel caps and POI enforces by
     * throwing. The other is the separator: an inline list is comma-separated with no escaping, so a
     * value containing a comma silently becomes two entries — {@code JPMORGAN CHASE BANK, N.A.} offers
     * itself as {@code JPMORGAN CHASE BANK} and {@code N.A.}, neither of which imports. A double quote
     * breaks the formula in the same way.
     *
     * <p>Length alone was the whole test while the only inline candidates were option sets of short
     * codes. Relation columns brought in names people wrote, and punctuation with them.
     */
    private static boolean fitsInline(List<String> codes) {
        if (inlineLength(codes) > INLINE_FORMULA_LIMIT) {
            return false;
        }
        for (String code : codes) {
            if (code.indexOf(',') >= 0 || code.indexOf('"') >= 0) {
                return false;
            }
        }
        return true;
    }

    /** Length of the values as Excel stores them inline: comma-separated, no quotes. */
    private static int inlineLength(List<String> codes) {
        int length = codes.size() - 1;
        for (String code : codes) {
            length += code.length();
        }
        return length;
    }

    private static Sheet createHiddenOptionsSheet(Workbook workbook) {
        Sheet existing = workbook.getSheet(OPTIONS_SHEET_NAME);
        if (existing != null) {
            return existing;
        }
        Sheet sheet = workbook.createSheet(OPTIONS_SHEET_NAME);
        workbook.setSheetHidden(workbook.getSheetIndex(sheet), true);
        if (sheet instanceof SXSSFSheet streamed) {
            // The export writes a streaming workbook, which keeps a window of rows in memory and
            // flushes the rest to disk — and a flushed row can never be written again. This sheet is
            // filled one COLUMN at a time, so every column after the first starts back at row 0, which
            // by then is gone. POI answers "Attempting to write a row[0] in the range [0,148] that is
            // already written to disk", the column loses its dropdown, and the download still succeeds:
            // a template that quietly has dropdowns on its first columns and none after, with which
            // ones survive decided by how long the earlier lists happened to be.
            //
            // -1 keeps every row of THIS sheet addressable until the workbook is written. It is bounded
            // by MAX_VALUES_PER_COLUMN rows and applies only here; the data sheets keep streaming.
            streamed.setRandomAccessWindowSize(-1);
        }
        return sheet;
    }

    private static void writeOptionsColumn(Sheet sheet, int columnIndex, List<String> codes) {
        for (int i = 0; i < codes.size(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                row = sheet.createRow(i);
            }
            row.createCell(columnIndex).setCellValue(codes.get(i));
        }
    }

    /** {@code _options!$A$1:$A$n} — absolute, so copying a cell does not shift the source. */
    private static String rangeReference(int columnIndex, int size) {
        String column = columnLetter(columnIndex);
        return String.format("%s!$%s$1:$%s$%d", OPTIONS_SHEET_NAME, column, column, size);
    }

    private static String columnLetter(int columnIndex) {
        StringBuilder letters = new StringBuilder();
        int index = columnIndex;
        while (index >= 0) {
            letters.insert(0, (char) ('A' + index % 26));
            index = index / 26 - 1;
        }
        return letters.toString();
    }
}
