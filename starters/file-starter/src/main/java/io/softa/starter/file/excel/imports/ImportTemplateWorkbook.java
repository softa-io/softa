package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.RowWriteHandler;
import org.apache.fesod.sheet.write.handler.WriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;
import org.apache.fesod.sheet.write.handler.context.RowWriteHandlerContext;
import org.apache.fesod.sheet.write.metadata.style.WriteCellStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.excel.export.ExcelSheetData;
import io.softa.starter.file.excel.export.support.OptionDropdownHandler;
import io.softa.starter.file.excel.export.support.OptionDropdownResolver;
import io.softa.starter.file.excel.style.ImportSheetStyleHandler;
import io.softa.starter.file.excel.style.TemporalColumnFormatHandler;

/**
 * The workbook an import template is filled in on.
 *
 * <p>One workbook for two uses: the blank template a person downloads to fill in, and a file exported
 * for re-import, which carries the records under the same header. Built in one place so the two cannot
 * drift — an exported file goes back in through the very template it came out of, and every column,
 * dropdown and date format it was written with is what that template reads.
 */
@Component
public class ImportTemplateWorkbook {

    /** The sheet beside the template's own that explains each column. */
    static final String INSTRUCTION_SHEET = "Import instructions";

    @Autowired
    private OptionDropdownResolver optionDropdownResolver;

    /**
     * The template's sheets: its own, with {@code rows} under the header, and the instruction sheet
     * when the template asks for one.
     *
     * @param template     the template, for its name, model, country, rule and instruction setting
     * @param importFields the columns, in order, as the caller's import of this template reads them
     * @param rows         the records to write, positional with {@code importFields}; empty for a blank
     *                     template
     */
    public List<ExcelSheetData> sheets(ImportTemplate template, List<ImportFieldDTO> importFields,
                                       List<List<Object>> rows) {
        List<List<Object>> dataRows = rows == null ? List.of() : rows;
        List<String> headers = importFields.stream().map(ImportFieldDTO::getHeader).toList();

        // 1) Main sheet: header row, plus a dropdown on every column whose values are a fixed list.
        // Resolved here rather than inside the handler so the query happens before the workbook is
        // serialised — a write handler runs during serialisation and should not be holding a
        // connection. An empty map simply means no column qualified.
        // The template's own country, not the request's: an administrator of a company in one country
        // may download another country's template, and inferring it from the request would fill that
        // template with the wrong country's values.
        Map<Integer, List<String>> dropdowns = optionDropdownResolver.resolve(
                template.getModelName(), importFields, template.getCountry(), template.getImportRule());
        ExcelSheetData mainSheetData = new ExcelSheetData(template.getName(), headers, dataRows,
                new WriteHandler[]{
                        // Header look, description comments, freeze, filter, widths, data-area grid.
                        ImportSheetStyleHandler.forTemplate(importFields, dataRows.size()),
                        new OptionDropdownHandler(dropdowns, dataRows.size()),
                        // Without this a date column is General, and the format a typed date takes is
                        // the reader's locale — contradicting the instruction sheet beside it.
                        TemporalColumnFormatHandler.forFields(template.getModelName(),
                                importFields.stream().map(ImportFieldDTO::getFieldName).toList())
                });
        List<ExcelSheetData> sheetDataList = new ArrayList<>();
        sheetDataList.add(mainSheetData);
        if (Boolean.TRUE.equals(template.getIncludeDescription())) {
            // 2) Instruction sheet: header row + a second row containing field descriptions.
            // Use the description configured on the import template field (not MetaField.description).
            List<Object> instructionRow = importFields.stream().<Object>map(f -> {
                String description = f.getDescription();
                return description == null ? "" : description;
            }).toList();
            ImportSheetStyleHandler instructionStyle = ImportSheetStyleHandler.forCompanionSheet(importFields);
            sheetDataList.add(new ExcelSheetData(INSTRUCTION_SHEET, headers, List.of(instructionRow),
                    new WriteHandler[]{
                            instructionStyle,
                            createInstructionWrapHandler(),
                            createInstructionRowHeightHandler(instructionStyle)
                    }));
        }
        return sheetDataList;
    }

    private static WriteHandler createInstructionWrapHandler() {
        return new CellWriteHandler() {
            @Override
            public void afterCellDispose(CellWriteHandlerContext context) {
                if (context.getHead()) {
                    return;
                }
                WriteCellStyle writeCellStyle = context.getFirstCellData().getOrCreateStyle();
                writeCellStyle.setWrapped(Boolean.TRUE);
                writeCellStyle.setVerticalAlignment(VerticalAlignment.TOP);
            }
        };
    }

    /** Sized against the widths {@code style} gives each column, which follow the column's header. */
    private static WriteHandler createInstructionRowHeightHandler(ImportSheetStyleHandler style) {
        return new RowWriteHandler() {
            @Override
            public void afterRowDispose(RowWriteHandlerContext context) {
                Row row = context.getRow();
                if (context.getHead() || row == null) {
                    return;
                }
                row.setHeightInPoints(calculateInstructionRowHeight(row, style));
            }
        };
    }

    private static float calculateInstructionRowHeight(Row row, ImportSheetStyleHandler style) {
        int estimatedLineCount = 1;
        short lastCellNum = row.getLastCellNum();
        if (lastCellNum <= 0) {
            return estimatedLineCount * 16f;
        }
        for (int i = 0; i < lastCellNum; i++) {
            Cell cell = row.getCell(i);
            if (cell == null) {
                continue;
            }
            String cellText = cell.toString();
            if (StringUtils.isBlank(cellText)) {
                continue;
            }
            estimatedLineCount = Math.max(estimatedLineCount,
                    ImportSheetStyleHandler.wrappedLines(cellText, style.columnWidth(i) - 4));
        }
        return estimatedLineCount * 16f;
    }
}
