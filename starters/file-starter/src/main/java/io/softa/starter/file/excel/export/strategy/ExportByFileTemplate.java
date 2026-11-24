package io.softa.starter.file.excel.export.strategy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.apache.fesod.sheet.write.metadata.fill.FillConfig;
import org.apache.poi.EmptyFileException;
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.service.FileService;
import io.softa.starter.file.dto.ExportResult;
import io.softa.starter.file.entity.ExportHistory;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.excel.export.support.ExcelUploadService;
import io.softa.starter.file.excel.export.support.ExportDataFetcher;
import io.softa.starter.file.excel.export.support.LargeIntegerAsTextHandler;

/**
 * Export by file template.
 * Export data based on the uploaded template file
 */
@Slf4j
@Component
public class ExportByFileTemplate implements ExportStrategy {

    private static final String HISTORY_MODEL = ExportHistory.class.getSimpleName();

    /**
     * For a template with rows below the data row: each record gets a row of its own, inserted at the data row, so
     * whatever is below — a total, a signature, notes — moves down instead of being written over by the second record
     * onwards. Fesod can only insert rows into a workbook it holds in memory whole, so this costs several times the
     * memory and time of a plain fill; a template with nothing below its data row is filled the plain, streaming way.
     */
    static final FillConfig FILL_PUSHING_ROWS_DOWN = FillConfig.builder().forceNewRow(Boolean.TRUE).build();

    /** For a template with nothing below its data row: records are written row after row, streaming. */
    static final FillConfig FILL_STREAMING = FillConfig.builder().forceNewRow(Boolean.FALSE).build();

    // {{ variable }} or {{ object.field }} in the uploaded template: dot-separated names, no empty segment.
    private static final Pattern VARIABLE_PATTERN =
            Pattern.compile("(?<!\\\\)\\{\\{\\s*([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*)\\s*}}");

    @Autowired
    private FileService fileService;

    @Autowired
    private ExportDataFetcher exportDataFetcher;

    @Autowired
    private ExcelUploadService excelUploadService;

    /**
     * Export data rows by file template.
     * The file template is a template file that contains the variables to be filled in.
     *
     * @param exportTemplate exportTemplate object
     * @param flexQuery the flexQuery of the exported conditions
     * @return fileInfo object with download URL
     */
    public ExportResult export(ExportTemplate exportTemplate, FlexQuery flexQuery) {
        PreparedTemplate template = readTemplate(exportTemplate);
        flexQuery.setFields(template.variables());
        List<Map<String, Object>> rows = exportDataFetcher.fetchRows(exportTemplate.getModelName(),
                exportTemplate.getCustomHandler(), flexQuery);
        // Fill in the data into the file template
        FileInfo fileInfo = this.fillAndUpload(exportTemplate.getFileName(), template, rows);
        return new ExportResult(fileInfo, rows.size());
    }

    @Override
    public ExportMode getMode() {
        return ExportMode.FILE_TEMPLATE;
    }

    @Override
    public ExportResult export(ExportContext exportContext) {
        return export(exportContext.getExportTemplate(), exportContext.getFlexQuery());
    }

    /** Downloads and prepares the template once: the fields to query and the workbook to fill both come from it. */
    private PreparedTemplate readTemplate(ExportTemplate exportTemplate) {
        try (InputStream inputStream = fileService.downloadStream(exportTemplate.getFileId())) {
            return prepareTemplate(inputStream, exportTemplate.getSheetName());
        } catch (IOException e) {
            throw new BusinessException("Failed to read the file template.", e);
        }
    }

    /**
     * Renders data into a file template and uploads the generated file to OSS.
     *
     * @param fileName the name of the generated file
     * @param template the prepared template
     * @param rows the data to be filled into the file template
     * @return fileInfo object with download URL
     */
    private FileInfo fillAndUpload(String fileName, PreparedTemplate template, List<Map<String, Object>> rows) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             // Use FesodSheet to write the template and fill in the data
             ExcelWriter excelWriter = FesodSheet.write(outputStream).withTemplate(template.stream()).build()) {
            // Create a write sheet and fill in the data. This path builds its own sheet rather than
            // going through ExcelWriterFactory — the template supplies the styling — so the one
            // handler that is about VALUES, not appearance, has to be named here as well.
            WriteSheet writeSheet = FesodSheet.writerSheet(template.sheetNo())
                    .registerWriteHandler(new LargeIntegerAsTextHandler())
                    .build();
            excelWriter.fill(rows, template.fillConfig(), writeSheet);
            // TODO: fill in the ENV related to current user
            excelWriter.finish();
            // Upload the Excel stream to OSS
            byte[] excelBytes = outputStream.toByteArray();
            // Stamped with the model that holds the file — the export history row — not the model exported,
            // as the other export modes do; otherwise attaching it to the history is refused.
            return excelUploadService.uploadExcelBytes(HISTORY_MODEL, fileName, excelBytes);
        } catch (Exception e) {
            throw new BusinessException("Failed to fill data into the file template {0}.", fileName, e);
        }
    }

    /**
     * The template with its placeholders in Fesod's syntax, the sheet the rows go into, and the fields that
     * sheet's placeholders name.
     *
     * @param bytes        the normalized template
     * @param sheetNo      the index of the template's sheet to fill
     * @param variables    the fields the sheet's placeholders name, which are the fields to query
     * @param rowsBelowData whether the sheet has any row below its data row, which the fill must push down
     */
    record PreparedTemplate(byte[] bytes, int sheetNo, Set<String> variables, boolean rowsBelowData) {
        InputStream stream() {
            return new ByteArrayInputStream(bytes);
        }

        FillConfig fillConfig() {
            return rowsBelowData ? FILL_PUSHING_ROWS_DOWN : FILL_STREAMING;
        }
    }

    /**
     * Normalizes the uploaded template and picks the sheet to fill: the sheet named {@code sheetName} when one is
     * configured, otherwise the first sheet that has a placeholder. Only that sheet's placeholders are rewritten and
     * queried; the other sheets — instructions, lookups — are left as they are.
     *
     * <p>The rows are filled as a list, one record per row starting at the data row, so every placeholder of the
     * sheet must sit on that one row. A placeholder above it (a title naming a field) would be repeated once per
     * record, and a record spread over two rows would have its rows written over each other; both are refused
     * here, with the rows named, rather than producing a workbook that looks filled but is not.
     *
     * <p>Fesod reads every brace of the sheet it fills, not only the placeholders: {@code {unit}} is a single-value
     * placeholder it blanks, {@code {.title}} is a list placeholder it repeats per record, and a mistyped
     * {@code {{ dept-name }}} would vanish with its field never queried. So any brace in the sheet that is not part
     * of a {@code {{ … }}} placeholder is refused, with the cell named — checked on the template as uploaded, before
     * the placeholders are rewritten into braces of Fesod's own.
     *
     * @throws BusinessException when the file is not an .xlsx workbook, the configured sheet does not exist, the
     *                           sheet has no placeholder, the placeholders span several rows, or a cell holds a brace
     *                           that is not a placeholder
     */
    static PreparedTemplate prepareTemplate(InputStream inputStream, String sheetName) throws IOException {
        try (Workbook workbook = openWorkbook(inputStream);
             ByteArrayOutputStream normalizedOutput = new ByteArrayOutputStream()) {
            int sheetNo = targetSheet(workbook, sheetName);
            Sheet sheet = workbook.getSheetAt(sheetNo);
            Set<String> variables = getVariablesInSheet(sheet);
            if (variables.isEmpty()) {
                throw new BusinessException("Sheet {0} of the file template has no placeholder such as '{{ name }}'"
                        + " to fill.", sheet.getSheetName());
            }
            refuseStrayBraces(sheet);
            // Any row below the data row, content or only formatting, is one the fill would otherwise write over.
            boolean rowsBelowData = sheet.getLastRowNum() > placeholderRows(sheet).iterator().next();
            normalizeSheetPlaceholders(sheet);
            workbook.write(normalizedOutput);
            return new PreparedTemplate(normalizedOutput.toByteArray(), sheetNo, variables, rowsBelowData);
        }
    }

    private static Workbook openWorkbook(InputStream inputStream) throws IOException {
        try {
            return new XSSFWorkbook(inputStream);
        } catch (POIXMLException | UnsupportedFileFormatException | EmptyFileException e) {
            throw new BusinessException("The file template is not an Excel .xlsx workbook, or the file is damaged.", e);
        }
    }

    private static void refuseStrayBraces(Sheet sheet) {
        forEachText(sheet, cell -> {
            String rest = VARIABLE_PATTERN.matcher(cell.getStringCellValue()).replaceAll("");
            if (rest.indexOf('{') >= 0 || rest.indexOf('}') >= 0) {
                throw new BusinessException("Cell {0} of sheet {1} in the file template has braces that are not a"
                        + " placeholder: {2}. Write placeholders as '{{ fieldName }}' or '{{ field.subField }}'; other"
                        + " braces cannot be used in this sheet.", cell.getAddress().formatAsString(),
                        sheet.getSheetName(), cell.getStringCellValue());
            }
        });
    }

    /**
     * The configured sheet, refused when the template has none of that name — falling back to another sheet would
     * fill the wrong one without a word; a configured sheet may be hidden. With none configured, the first visible
     * sheet holding a placeholder, so a leading instructions sheet is not mistaken for the data. When no visible
     * sheet has one, the template is refused, naming a hidden sheet that does, since that one is filled only when
     * configured by name.
     */
    private static int targetSheet(Workbook workbook, String sheetName) {
        if (StringUtils.isNotBlank(sheetName)) {
            int index = workbook.getSheetIndex(sheetName);
            if (index < 0) {
                throw new BusinessException("The file template has no sheet named {0}.", sheetName);
            }
            return index;
        }
        String hiddenWithPlaceholders = null;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (placeholderRows(workbook.getSheetAt(i)).isEmpty()) {
                continue;
            }
            if (!workbook.isSheetHidden(i) && !workbook.isSheetVeryHidden(i)) {
                return i;
            }
            if (hiddenWithPlaceholders == null) {
                hiddenWithPlaceholders = workbook.getSheetName(i);
            }
        }
        if (hiddenWithPlaceholders != null) {
            throw new BusinessException("No visible sheet of the file template has a placeholder such as '{{ name }}'."
                    + " Sheet {0} has placeholders but is hidden: set the sheet name to {0} to fill it.",
                    hiddenWithPlaceholders);
        }
        // No arguments, so the message is not run through MessageFormat and its braces need no quoting.
        throw new BusinessException(
                "No visible sheet of the file template has a placeholder such as {{ name }} to fill.");
    }

    /**
     * Get all the variable parameters in the sheet, which must all be on one row.
     * The uploaded template uses `{{ variable }}`; see {@link #normalizeCellValue} for what Fesod is handed.
     * Cases of variable extraction:
     *      - `{{ name }}` -> `name`
     *      - `{{  name }}` -> `name`
     *      - `Hello, {{ name123 }}!` -> `name123`
     *      - `{{ name }}, {{ orderNumber }}` -> `name`, `orderNumber`
     *      - `{{ deptId.name }}` -> `deptId.name`
     *      - `Hello, {{ name }}, {{}}` -> `name`
     *      - `Hello, {{ name }}, \{{ id }}` -> `name`
     * The leftovers of the last two (`{{}}`, `\{{ id }}`) are braces Fesod would read, and the sheet is refused for
     * them before its placeholders are rewritten.
     *
     * @param sheet the sheet to fill
     * @return all variables in the sheet
     */
    private static Set<String> getVariablesInSheet(Sheet sheet) {
        Set<Integer> rows = placeholderRows(sheet);
        if (rows.size() > 1) {
            List<Integer> rowNumbers = rows.stream().map(row -> row + 1).toList();
            throw new BusinessException("Sheet {0} of the file template has placeholders on rows {1}. "
                    + "Put every placeholder on the one data row: each record fills a row of its own from there.",
                    sheet.getSheetName(), rowNumbers);
        }
        Set<String> variables = new LinkedHashSet<>();
        forEachText(sheet, cell -> {
            Matcher matcher = VARIABLE_PATTERN.matcher(cell.getStringCellValue());
            while (matcher.find()) {
                variables.add(matcher.group(1));
            }
        });
        return variables;
    }

    /** The 0-based indexes of the rows that hold a placeholder. */
    private static Set<Integer> placeholderRows(Sheet sheet) {
        Set<Integer> rows = new TreeSet<>();
        forEachText(sheet, cell -> {
            if (VARIABLE_PATTERN.matcher(cell.getStringCellValue()).find()) {
                rows.add(cell.getRowIndex());
            }
        });
        return rows;
    }

    private static void normalizeSheetPlaceholders(Sheet sheet) {
        forEachText(sheet, cell -> {
            String normalized = normalizeCellValue(cell.getStringCellValue());
            if (!normalized.equals(cell.getStringCellValue())) {
                cell.setCellValue(normalized);
            }
        });
    }

    private static void forEachText(Sheet sheet, Consumer<Cell> action) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING) {
                    action.accept(cell);
                }
            }
        }
    }

    /**
     * Rewrites each {@code {{ variable }}} into Fesod's collection placeholder {@code {.variable}}. The rows are
     * filled as a list, and Fesod fills a list only into placeholders that start with a dot: a bare
     * {@code {variable}} is a single value, which a list fill blanks out, and {@code {a.b}} names a separate
     * list {@code a}, so a cascaded {@code deptId.name} would be left empty as well. The rest of the cell text is
     * kept, e.g. {@code ID-{{ code }}} becomes {@code ID-{.code}}.
     */
    static String normalizeCellValue(String cellValue) {
        Matcher matcher = VARIABLE_PATTERN.matcher(cellValue);
        StringBuilder normalized = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(normalized, Matcher.quoteReplacement("{." + matcher.group(1) + "}"));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

}
