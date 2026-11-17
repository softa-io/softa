package io.softa.starter.file.excel.style;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.fesod.sheet.constant.OrderConstant;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.RowWriteHandler;
import org.apache.fesod.sheet.write.handler.SheetWriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;
import org.apache.fesod.sheet.write.handler.context.RowWriteHandlerContext;
import org.apache.fesod.sheet.write.handler.context.SheetWriteHandlerContext;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;

import io.softa.starter.file.constant.FileConstant;
import io.softa.starter.file.dto.ImportFieldDTO;

/**
 * The look of an import sheet: the downloadable template and the result file written back after an
 * import or a validation run.
 *
 * <p>Header cells are light with dark bold text, so the header reads as part of the form rather than
 * as a banner. A required column is told apart by a warm fill and an orange bottom rule, not by a
 * marker in its text — the importer matches columns by header text, so the text has to stay exactly
 * what the template declares. A column's description goes into the header cell's comment, where it is
 * one hover away from the column it explains.
 *
 * <p>The sheet freezes the header row, turns on a filter over the header, and sizes each column from
 * its header text. The data area is white with a light grid. A result file adds two
 * trailing columns — the per-row outcome and the failure reason — with a dark header so they stand
 * apart from the template's own columns, and colours each outcome.
 *
 * <p>Fesod expresses colours as palette indexes only, so exact colours are set on POI's XSSF styles
 * directly. That is why the cell work runs after Fesod's own style fill ({@link OrderConstant#FILL_STYLE}):
 * it starts from the style Fesod produced — keeping whatever number or date format it carries — and
 * derives a coloured copy, cached so a large file does not exhaust the workbook's style table.
 */
public class ImportSheetStyleHandler implements SheetWriteHandler, RowWriteHandler, CellWriteHandler {

    /** After Fesod's style fill, so the style it wrote to the cell is the starting point, not the result. */
    private static final int ORDER = OrderConstant.FILL_STYLE + 1000;

    private static final int COMMENT_CHARS_PER_LINE = 40;
    private static final int COMMENT_MAX_LINES = 12;

    private enum Mode {
        /** The template's main sheet: everything, including comments and the column defaults. */
        TEMPLATE,
        /** A companion sheet such as the instruction sheet: header look and column widths only. */
        HEADER_ONLY,
        /** A result file: the template columns plus the outcome and failure reason columns. */
        RESULT
    }

    private enum Variant {
        BODY, SUCCESS, FAILED, FAILED_REASON
    }

    private final Mode mode;
    private final List<String> headers;
    private final List<Boolean> required;
    private final List<String> descriptions;
    /** Index of the outcome column in RESULT mode, -1 otherwise; the failure reason follows it. */
    private final int resultColumn;
    private final String successLabel;
    private final String failedLabel;
    /**
     * Last row the filter covers: the header alone for a blank template, every data row for a result
     * file or a template exported with records.
     */
    private final int lastFilterRow;

    private final Map<Workbook, Map<String, CellStyle>> styleCache = new IdentityHashMap<>();
    private final Map<Sheet, Drawing<?>> drawings = new IdentityHashMap<>();

    private ImportSheetStyleHandler(Mode mode, List<ImportFieldDTO> fields, List<String> extraHeaders,
                                    String successLabel, String failedLabel, int lastFilterRow) {
        this.mode = mode;
        this.headers = new ArrayList<>();
        this.required = new ArrayList<>();
        this.descriptions = new ArrayList<>();
        for (ImportFieldDTO field : fields) {
            headers.add(field.getHeader());
            required.add(Boolean.TRUE.equals(field.getRequired()));
            descriptions.add(field.getDescription());
        }
        this.resultColumn = mode == Mode.RESULT ? fields.size() : -1;
        for (String extra : extraHeaders) {
            headers.add(extra);
            required.add(false);
            descriptions.add(null);
        }
        this.successLabel = successLabel;
        this.failedLabel = failedLabel;
        this.lastFilterRow = lastFilterRow;
    }

    /** The template's main sheet: header look, comments, freeze, filter, widths and the data-area grid. */
    public static ImportSheetStyleHandler forTemplate(List<ImportFieldDTO> fields) {
        return forTemplate(fields, 0);
    }

    /**
     * The template's main sheet with {@code dataRows} records written under the header — a file
     * exported for re-import — so the filter covers them, as it covers a result file's rows.
     */
    public static ImportSheetStyleHandler forTemplate(List<ImportFieldDTO> fields, int dataRows) {
        return new ImportSheetStyleHandler(Mode.TEMPLATE, fields, List.of(), null, null, Math.max(0, dataRows));
    }

    /** A companion sheet of a template (the instruction sheet): the same header look and widths only. */
    public static ImportSheetStyleHandler forCompanionSheet(List<ImportFieldDTO> fields) {
        return new ImportSheetStyleHandler(Mode.HEADER_ONLY, fields, List.of(), null, null, 0);
    }

    /**
     * A result file: the template columns followed by {@code resultHeader} and {@code reasonHeader}.
     *
     * @param successLabel the outcome text of a row that went through
     * @param failedLabel  the outcome text of a row that did not
     * @param dataRows     number of data rows, so the filter covers all of them
     */
    public static ImportSheetStyleHandler forResult(List<ImportFieldDTO> fields, String resultHeader,
                                                    String reasonHeader, String successLabel,
                                                    String failedLabel, int dataRows) {
        return new ImportSheetStyleHandler(Mode.RESULT, fields, List.of(resultHeader, reasonHeader),
                successLabel, failedLabel, dataRows);
    }


    @Override
    public int order() {
        return ORDER;
    }

    // ───────────────────────── sheet ─────────────────────────

    @Override
    public void afterSheetCreate(SheetWriteHandlerContext context) {
        Sheet sheet = context.getWriteSheetHolder().getSheet();
        useBodyFontByDefault(sheet.getWorkbook());
        for (int i = 0; i < headers.size(); i++) {
            sheet.setColumnWidth(i, columnWidth(i) * 256);
        }
        if (mode == Mode.HEADER_ONLY || headers.isEmpty()) {
            return;
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, lastFilterRow), 0, headers.size() - 1));
        if (mode == Mode.TEMPLATE) {
            applyDataAreaDefaults(sheet);
        }
    }

    /**
     * Width in characters, within the bounds. Bold text runs about a fifth wider than the width unit,
     * which is measured on a regular character, and the filter button takes the right edge of the
     * cell; sized on the plain length, a header such as "Date Of Birth" was cut short. A header past
     * the maximum wraps onto a second line, which the header row is tall enough to show.
     */
    public int columnWidth(int column) {
        if (column == resultColumn + 1 && mode == Mode.RESULT) {
            return FileConstant.IMPORT_MAX_COLUMN_WIDTH;
        }
        String header = headers.get(column);
        int length = header == null ? 0 : (int) Math.ceil(header.length() * BOLD_WIDTH_FACTOR) + FILTER_BUTTON_WIDTH;
        return Math.clamp(length, FileConstant.IMPORT_MIN_COLUMN_WIDTH, FileConstant.IMPORT_MAX_COLUMN_WIDTH);
    }

    /** How much wider bold header text runs than the regular character the width unit is measured on. */
    private static final double BOLD_WIDTH_FACTOR = 1.2;
    /** Characters the header's filter button covers at the right edge of the cell. */
    private static final int FILTER_BUTTON_WIDTH = 5;

    /**
     * The workbook's default font, which every cell without a font of its own uses — the cells a person
     * types into. Left alone, it is the application's own default (SimSun in a Chinese locale), so what
     * was filled in did not match the template around it.
     */
    private static void useBodyFontByDefault(Workbook workbook) {
        Font font = workbook.getFontAt(0);
        font.setFontName(FileConstant.DEFAULT_EXCEL_FONT_NAME);
        font.setFontHeightInPoints(FileConstant.DEFAULT_EXCEL_BODY_FONT_SIZE);
    }

    /**
     * An empty template has no data cells to style, so the grid goes on the column defaults — the
     * style Excel gives a cell the user creates by typing. A column that already carries a default
     * (a date column's format) keeps it; the grid is added to a copy of it.
     */
    private void applyDataAreaDefaults(Sheet sheet) {
        Workbook workbook = sheet.getWorkbook();
        for (int i = 0; i < headers.size(); i++) {
            CellStyle base = sheet.getColumnStyle(i);
            CellStyle style = cached(workbook, "column:" + (base == null ? "none" : base.getIndex()), () -> {
                CellStyle created = workbook.createCellStyle();
                if (base != null) {
                    created.cloneStyleFrom(base);
                }
                applyGrid(created);
                return created;
            });
            sheet.setDefaultColumnStyle(i, style);
        }
    }

    // ───────────────────────── row ─────────────────────────

    @Override
    public void afterRowDispose(RowWriteHandlerContext context) {
        Row row = context.getRow();
        if (row == null) {
            return;
        }
        if (Boolean.TRUE.equals(context.getHead())) {
            row.setHeightInPoints(FileConstant.IMPORT_HEAD_ROW_HEIGHT);
        } else if (mode == Mode.RESULT) {
            fitFailureReason(row);
        }
    }

    /**
     * Tall enough for the wrapped failure reason. Excel fits a row to wrapped text on its own only for
     * rows it lays out itself, and some spreadsheet apps never do, so a reason listing several missing
     * fields stayed a single line running far past its column. The line count is estimated from the
     * text's display width against the column width — close enough for a row of plain sentences.
     */
    private void fitFailureReason(Row row) {
        Cell reason = row.getCell(resultColumn + 1);
        String text = reason == null ? "" : textOf(reason);
        if (text.isEmpty()) {
            return;
        }
        int lines = wrappedLines(text, FileConstant.IMPORT_MAX_COLUMN_WIDTH - 2);
        if (lines > 1) {
            row.setHeightInPoints(lines * FileConstant.IMPORT_RESULT_LINE_HEIGHT);
        }
    }

    /**
     * Lines {@code text} takes when wrapped in a column {@code width} characters wide, each explicit
     * line break starting a new one.
     */
    public static int wrappedLines(String text, int width) {
        int perLine = Math.max(1, width);
        int lines = 0;
        for (String segment : text.split("\\R", -1)) {
            lines += Math.max(1, (displayWidth(segment) + perLine - 1) / perLine);
        }
        return Math.max(1, lines);
    }

    /**
     * Width of {@code text} in column-width units: a CJK or other full-width character takes two, as it
     * does on screen. Counted by characters alone, a Chinese reason was estimated at half its width
     * and the row came out too short to show it.
     */
    static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += isWide(codePoint) ? 2 : 1;
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private static boolean isWide(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F)
                || (codePoint >= 0x2E80 && codePoint <= 0xA4CF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFE30 && codePoint <= 0xFE4F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFF60)
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                || (codePoint >= 0x20000 && codePoint <= 0x3FFFD);
    }

    // ───────────────────────── cell ─────────────────────────

    @Override
    public void afterCellDispose(CellWriteHandlerContext context) {
        Cell cell = context.getCell();
        Integer column = context.getColumnIndex();
        if (cell == null || column == null || column < 0 || column >= headers.size()) {
            return;
        }
        Workbook workbook = cell.getSheet().getWorkbook();
        if (Boolean.TRUE.equals(context.getHead())) {
            cell.setCellStyle(headerStyle(workbook, column));
            if (mode == Mode.TEMPLATE) {
                addComment(cell, column);
            }
        } else {
            cell.setCellStyle(bodyStyle(workbook, cell.getCellStyle(), variantOf(cell, column)));
        }
    }

    private Variant variantOf(Cell cell, int column) {
        if (mode != Mode.RESULT || column < resultColumn) {
            return Variant.BODY;
        }
        if (column == resultColumn) {
            String text = textOf(cell);
            if (text.equals(failedLabel)) {
                return Variant.FAILED;
            }
            return text.equals(successLabel) ? Variant.SUCCESS : Variant.BODY;
        }
        // The failure reason column: tinted on a failed row. The outcome cell is to its left, so it
        // has been written by the time this one is.
        Cell outcome = cell.getRow().getCell(resultColumn);
        return outcome != null && textOf(outcome).equals(failedLabel) ? Variant.FAILED_REASON : Variant.BODY;
    }

    private static String textOf(Cell cell) {
        return cell.getCellType() == CellType.STRING ? StringUtils.defaultString(cell.getStringCellValue()) : "";
    }

    private CellStyle headerStyle(Workbook workbook, int column) {
        boolean outcomeColumn = mode == Mode.RESULT && column >= resultColumn;
        String key = outcomeColumn ? "head:result" : (required.get(column) ? "head:required" : "head:optional");
        return cached(workbook, key, () -> {
            String fill;
            String border;
            String text;
            if (outcomeColumn) {
                fill = FileConstant.IMPORT_RESULT_HEAD_FILL;
                border = FileConstant.IMPORT_RESULT_HEAD_BORDER;
                text = FileConstant.IMPORT_RESULT_HEAD_TEXT_COLOR;
            } else if (required.get(column)) {
                fill = FileConstant.IMPORT_REQUIRED_HEAD_FILL;
                border = FileConstant.IMPORT_REQUIRED_HEAD_BORDER;
                text = FileConstant.IMPORT_HEAD_TEXT_COLOR;
            } else {
                fill = FileConstant.IMPORT_OPTIONAL_HEAD_FILL;
                border = FileConstant.IMPORT_OPTIONAL_HEAD_BORDER;
                text = FileConstant.IMPORT_HEAD_TEXT_COLOR;
            }
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setFontName(FileConstant.DEFAULT_EXCEL_FONT_NAME);
            font.setFontHeightInPoints(FileConstant.IMPORT_HEAD_FONT_SIZE);
            font.setBold(true);
            setFontColor(font, text);
            style.setFont(font);
            setFill(style, fill);
            style.setWrapText(true);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setAlignment(HorizontalAlignment.CENTER);
            applyGrid(style);
            style.setBorderBottom(BorderStyle.MEDIUM);
            if (style instanceof XSSFCellStyle xssf) {
                xssf.setBottomBorderColor(color(border));
            }
            return style;
        });
    }

    private CellStyle bodyStyle(Workbook workbook, CellStyle source, Variant variant) {
        String key = "body:" + (source == null ? "none" : source.getIndex()) + ":" + variant;
        return cached(workbook, key, () -> {
            CellStyle style = workbook.createCellStyle();
            if (source != null) {
                style.cloneStyleFrom(source);
            }
            applyGrid(style);
            if (mode == Mode.RESULT) {
                // A row grows with its failure reason; the rest of it stays level with the reason.
                style.setVerticalAlignment(VerticalAlignment.CENTER);
            }
            if (variant == Variant.FAILED_REASON) {
                setFill(style, FileConstant.IMPORT_FAILED_REASON_FILL);
                style.setWrapText(true);
            }
            if (variant == Variant.SUCCESS || variant == Variant.FAILED) {
                Font font = workbook.createFont();
                Font base = source == null ? null : workbook.getFontAt(source.getFontIndex());
                font.setFontName(base == null ? FileConstant.DEFAULT_EXCEL_FONT_NAME : base.getFontName());
                font.setFontHeightInPoints(base == null
                        ? FileConstant.DEFAULT_EXCEL_BODY_FONT_SIZE : base.getFontHeightInPoints());
                font.setBold(true);
                setFontColor(font, variant == Variant.SUCCESS
                        ? FileConstant.IMPORT_SUCCESS_TEXT_COLOR : FileConstant.IMPORT_FAILED_TEXT_COLOR);
                style.setFont(font);
            }
            return style;
        });
    }

    /** White fill and a thin light grid on every side. */
    private static void applyGrid(CellStyle style) {
        if (style.getFillPattern() == FillPatternType.NO_FILL) {
            setFill(style, FileConstant.IMPORT_BODY_FILL);
        }
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        if (style instanceof XSSFCellStyle xssf) {
            XSSFColor grid = color(FileConstant.IMPORT_GRID_BORDER);
            xssf.setTopBorderColor(grid);
            xssf.setBottomBorderColor(grid);
            xssf.setLeftBorderColor(grid);
            xssf.setRightBorderColor(grid);
        }
    }

    private static void setFill(CellStyle style, String hex) {
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        if (style instanceof XSSFCellStyle xssf) {
            xssf.setFillForegroundColor(color(hex));
        }
    }

    private static void setFontColor(Font font, String hex) {
        if (font instanceof XSSFFont xssf) {
            xssf.setColor(color(hex));
        }
    }

    static XSSFColor color(String hex) {
        int rgb = Integer.parseInt(hex, 16);
        return new XSSFColor(new byte[]{(byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb}, null);
    }

    private CellStyle cached(Workbook workbook, String key, java.util.function.Supplier<CellStyle> factory) {
        return styleCache.computeIfAbsent(workbook, w -> new HashMap<>()).computeIfAbsent(key, k -> factory.get());
    }

    // ───────────────────────── comments ─────────────────────────

    /**
     * The column's description as the header cell's comment. A required column's comment opens with
     * {@value FileConstant#IMPORT_REQUIRED_NOTE}, so the reason the header is highlighted is spelled out
     * where the reader looks; a description that already opens with it is not repeated.
     */
    static String commentText(boolean required, String description) {
        String text = StringUtils.trimToEmpty(description);
        if (!required) {
            return text.isEmpty() ? null : text;
        }
        if (StringUtils.startsWithIgnoreCase(text, FileConstant.IMPORT_REQUIRED_NOTE)) {
            return text;
        }
        return text.isEmpty() ? FileConstant.IMPORT_REQUIRED_NOTE : FileConstant.IMPORT_REQUIRED_NOTE + " " + text;
    }

    private void addComment(Cell cell, int column) {
        String text = commentText(required.get(column), descriptions.get(column));
        if (text == null) {
            return;
        }
        Sheet sheet = cell.getSheet();
        Workbook workbook = sheet.getWorkbook();
        CreationHelper helper = workbook.getCreationHelper();
        Drawing<?> drawing = drawings.computeIfAbsent(sheet, Sheet::createDrawingPatriarch);
        ClientAnchor anchor = helper.createClientAnchor();
        int lines = Math.min(COMMENT_MAX_LINES, 2 + text.length() / COMMENT_CHARS_PER_LINE);
        anchor.setCol1(column + 1);
        anchor.setCol2(column + 4);
        anchor.setRow1(0);
        anchor.setRow2(lines);
        Comment comment = drawing.createCellComment(anchor);
        // The drawing files a new comment under the anchor's top-left cell, which is where the box
        // shows — one column to the right. Re-filed under the header cell it explains.
        comment.setAddress(cell.getRowIndex(), column);
        comment.setString(helper.createRichTextString(text));
        cell.setCellComment(comment);
    }
}
