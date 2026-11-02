package io.softa.starter.file.service.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.ValidationException;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.excel.imports.ImportHeaderMatcher;

import static io.softa.framework.orm.constant.FileConstant.FAILED_REASON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Columns the caller may not write are out of their template; a file still carrying them — made from
 * someone else's template — is read through the real reader, and only the rows that fill one fail.
 */
class ImportWithheldColumnsTest {

    private static final ImportFieldDTO NAME = field("Full Name", "fullName", true);
    private static final ImportFieldDTO METHOD = field("Method of Payment", "empBankAccountId.methodOfPayment", true);

    private final ImportServiceImpl importService = new ImportServiceImpl();

    private static ImportFieldDTO field(String header, String fieldName, boolean required) {
        ImportFieldDTO dto = new ImportFieldDTO();
        dto.setHeader(header);
        dto.setFieldName(fieldName);
        dto.setRequired(required);
        return dto;
    }

    private static ImportTemplateDTO template(boolean skipException) {
        ImportTemplateDTO dto = new ImportTemplateDTO();
        dto.setImportFields(new ArrayList<>(List.of(NAME)));
        dto.getWithheldFields().add(METHOD);
        dto.setSkipException(skipException);
        return dto;
    }

    private static byte[] workbookOf(List<String> headers, List<List<String>> rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = FesodSheet.write(out).build()) {
            WriteSheet sheet = FesodSheet.writerSheet(0, "Sheet1").head(headers.stream().map(List::of).toList()).build();
            writer.write(rows.stream().map(r -> (List<String>) new ArrayList<>(r)).toList(), sheet);
            writer.finish();
        }
        return out.toByteArray();
    }

    private List<Map<String, Object>> read(ImportTemplateDTO template, byte[] bytes) throws Exception {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return importService.extractDataFromExcel(ImportHeaderMatcher.of(template.getImportFields()),
                    ImportHeaderMatcher.of(template.getWithheldFields()), "upload.xlsx", in);
        }
    }

    @Test
    void theCallersOwnTemplateWithoutTheColumnIsComplete() throws Exception {
        ImportTemplateDTO template = template(true);
        List<Map<String, Object>> rows = read(template, workbookOf(List.of("Full Name"), List.of(List.of("Ada"))));

        importService.refuseWithheldValues(rows, template, false);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("fullName", "Ada");
            assertThat(row).doesNotContainKey(FAILED_REASON);
        });
    }

    @Test
    void aFilledWithheldColumnFailsItsRowAndAnEmptyOneIsIgnored() throws Exception {
        ImportTemplateDTO template = template(true);
        List<Map<String, Object>> rows = read(template, workbookOf(List.of("Full Name", "Method of Payment"),
                List.of(List.of("Ada", "Cash"), List.of("Ben", ""))));

        importService.refuseWithheldValues(rows, template, false);

        assertThat(rows.get(0)).containsEntry(FAILED_REASON, "You do not have permission to set Method of Payment.")
                .doesNotContainKey("empBankAccountId.methodOfPayment");
        // Left empty, the column is as good as absent: the row imports as from the caller's own template.
        assertThat(rows.get(1)).doesNotContainKeys(FAILED_REASON, "empBankAccountId.methodOfPayment");
    }

    @Test
    void aReasonAlreadyOnTheRowIsKept() {
        ImportTemplateDTO template = template(true);
        Map<String, Object> row = new HashMap<>(Map.of("fullName", "Ada", "empBankAccountId.methodOfPayment", "Cash",
                FAILED_REASON, "Earlier reason"));

        importService.refuseWithheldValues(new ArrayList<>(List.of(row)), template, false);

        assertThat(row.get(FAILED_REASON)).isEqualTo("Earlier reason; You do not have permission to set Method of Payment.");
    }

    @Test
    void aValidationReportsTheRowEvenWithoutSkipException() {
        // Validation reports every row: the file does not stop at the first filled withheld column, as an
        // import without skipException does — the row carries the reason and the rest are still checked.
        ImportTemplateDTO template = template(false);
        Map<String, Object> filled = new HashMap<>(Map.of("fullName", "Ada", "empBankAccountId.methodOfPayment", "Cash"));
        Map<String, Object> empty = new HashMap<>(Map.of("fullName", "Ben"));

        importService.refuseWithheldValues(new ArrayList<>(List.of(filled, empty)), template, true);

        assertThat(filled).containsEntry(FAILED_REASON, "You do not have permission to set Method of Payment.");
        assertThat(empty).doesNotContainKey(FAILED_REASON);
    }

    @Test
    void withoutSkipExceptionTheFileFails() {
        ImportTemplateDTO template = template(false);
        Map<String, Object> row = new HashMap<>(Map.of("fullName", "Ada", "empBankAccountId.methodOfPayment", "Cash"));

        assertThatThrownBy(() -> importService.refuseWithheldValues(new ArrayList<>(List.of(row)), template, false))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Method of Payment");
    }
}
