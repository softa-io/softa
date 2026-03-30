package io.softa.starter.file.excel.export;

import java.lang.reflect.Method;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import io.softa.starter.file.excel.export.strategy.ExportByFileTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExportByFileTemplateTest {

    @Test
    @SuppressWarnings("unchecked")
    void getVariablesInWorkbookExtractsBareVariableNamesAndSkipsEscapedTokens() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("sheet1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("Hello, {{name}}, {{orderNumber}}");
            row.createCell(1).setCellValue("{{deptId.name}}");
            row.createCell(2).setCellValue("\\{{ignored}}");

            Method method = ExportByFileTemplate.class.getDeclaredMethod("getVariablesInWorkbook", Workbook.class);
            method.setAccessible(true);

            Set<String> variables = (Set<String>) method.invoke(null, workbook);

            assertEquals(Set.of("name", "orderNumber", "deptId.name"), variables);
            assertFalse(variables.contains("{{name}}"));
            assertFalse(variables.contains("ignored"));
        }
    }
}
