package io.softa.starter.file.excel.export.strategy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
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

    // {{ variable }} or {{ object.field }} in the uploaded template.
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("(?<!\\\\)\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

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
        // TODO: cache the extracted fields in the exportTemplate
        Set<String> fields = extractVariablesOfFileTemplate(exportTemplate.getFileId());
        flexQuery.setFields(fields);
        List<Map<String, Object>> rows = exportDataFetcher.fetchRows(exportTemplate.getModelName(),
                exportTemplate.getCustomHandler(), flexQuery);
        // Fill in the data into the file template
        FileInfo fileInfo = this.generateByFileTemplateAndUpload(exportTemplate, rows);
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

    /**
     * Renders data into a file template and uploads the generated file to OSS.
     *
     * @param exportTemplate the export template object
     * @param rows the data to be filled into the file template
     * @return fileInfo object with download URL
     */
    private FileInfo generateByFileTemplateAndUpload(ExportTemplate exportTemplate, List<Map<String, Object>> rows) {
        String fileName = exportTemplate.getFileName();
        try (PreparedTemplate template = prepareTemplate(fileService.downloadStream(exportTemplate.getFileId()),
                     exportTemplate.getSheetName());
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             // Use FesodSheet to write the template and fill in the data
             ExcelWriter excelWriter = FesodSheet.write(outputStream).withTemplate(template.stream()).build()) {
            // Create a write sheet and fill in the data. This path builds its own sheet rather than
            // going through ExcelWriterFactory — the template supplies the styling — so the one
            // handler that is about VALUES, not appearance, has to be named here as well.
            WriteSheet writeSheet = FesodSheet.writerSheet(template.sheetNo())
                    .registerWriteHandler(new LargeIntegerAsTextHandler())
                    .build();
            excelWriter.fill(rows, writeSheet);
            // TODO: fill in the ENV related to current user
            excelWriter.finish();
            // Upload the Excel stream to OSS
            byte[] excelBytes = outputStream.toByteArray();
            // Stamped with the model that holds the file — the export history row — not the model exported,
            // as the other export modes do; otherwise attaching it to the history is refused.
            return excelUploadService.uploadExcelBytes(HISTORY_MODEL, fileName, excelBytes);
        } catch (Exception e) {
            throw new BusinessException("Failed to fill data into the file template {}.", fileName, e);
        }
    }

    /**
     * Get all the variable parameters in the file template.
     *
     * @param fileId the ID of the file template
     * @return all variables in the template
     */
    private Set<String> extractVariablesOfFileTemplate(Long fileId) {
        try (InputStream inputStream = fileService.downloadStream(fileId);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            return getVariablesInWorkbook(workbook);
        } catch (IOException e) {
            throw new BusinessException("Failed to read the file template.", e);
        }
    }

    /**
     * Get all the variable parameters in the Workbook.
     * The uploaded template uses `{{ variable }}`; see {@link #normalizeCellValue} for what Fesod is handed.
     * Cases of variable extraction:
     *      - `{{ name }}` -> `name`
     *      - `{{  name }}` -> `name`
     *      - `Hello, {{ name123 }}!` -> `name123`
     *      - `{{ name }}, {{ orderNumber }}` -> `name`, `orderNumber`
     *      - `{{ deptId.name }}` -> `deptId.name`
     *      - `Hello, {{ name }}, {{}}` -> `name`
     *      - `Hello, {{ name }}, \{{ id }}` -> `name`
     *
     * @param workbook workbook object
     * @return all variables in the template
     */
    private static Set<String> getVariablesInWorkbook(Workbook workbook) {
        Set<String> variables = new HashSet<>();
        // Iterate over all sheets
        for(Sheet sheet : workbook) {
            // Iterate over all rows in each sheet
            for(Row row : sheet) {
                // Iterate over all cells in each row
                for(Cell cell : row) {
                    if(cell.getCellType() == CellType.STRING) {
                        String cellValue = cell.getStringCellValue();
                        // Use regular expressions to match the variable format {{ variable }}
                        Matcher matcher = VARIABLE_PATTERN.matcher(cellValue);
                        while(matcher.find()) {
                            variables.add(matcher.group(1));
                        }
                    }
                }
            }
        }
        return variables;
    }

    /**
     * The template with its placeholders in Fesod's syntax, and the sheet the rows go into.
     *
     * @param stream  the normalized template
     * @param sheetNo the index of the template's sheet to fill
     */
    record PreparedTemplate(InputStream stream, int sheetNo) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    /**
     * Normalizes the uploaded template and picks the sheet to fill: the template's sheet named {@code sheetName}
     * when it has one, otherwise its first sheet. Filling by a name the template does not have makes Fesod append
     * a new, empty sheet and leave the template's own placeholders unfilled — which is what filling by the file
     * name used to do whenever the template's sheet was called anything else.
     */
    static PreparedTemplate prepareTemplate(InputStream inputStream, String sheetName) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(inputStream);
             ByteArrayOutputStream normalizedOutput = new ByteArrayOutputStream()) {
            normalizeWorkbookPlaceholders(workbook);
            int sheetNo = StringUtils.isBlank(sheetName) ? 0 : Math.max(workbook.getSheetIndex(sheetName), 0);
            workbook.write(normalizedOutput);
            return new PreparedTemplate(new ByteArrayInputStream(normalizedOutput.toByteArray()), sheetNo);
        }
    }

    private static void normalizeWorkbookPlaceholders(Workbook workbook) {
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.STRING) {
                        String normalized = normalizeCellValue(cell.getStringCellValue());
                        if (!normalized.equals(cell.getStringCellValue())) {
                            cell.setCellValue(normalized);
                        }
                    }
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
            matcher.appendReplacement(normalized, Matcher.quoteReplacement("{." + matcher.group(1).trim() + "}"));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

}
