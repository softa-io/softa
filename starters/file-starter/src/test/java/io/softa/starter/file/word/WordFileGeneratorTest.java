package io.softa.starter.file.word;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Word template prints a date or time as the API writes it — not as its ISO {@code toString()} — in a
 * plain tag and in looping table rows alike.
 */
class WordFileGeneratorTest {

    private static final LocalDateTime SIGNED = LocalDateTime.of(2026, 11, 12, 13, 0, 40, 123_456_789);

    @Test
    void aDateTimeTag_printsInThePlatformFormat() throws IOException {
        byte[] template = template(document ->
                document.createParagraph().createRun().setText("Signed on {{signedTime}}"));

        try (XWPFDocument rendered = render(template, Map.of("signedTime", SIGNED))) {
            assertEquals("Signed on 2026-11-12 13:00:40", rendered.getParagraphs().getFirst().getText());
        }
    }

    @Test
    void loopingTableRows_printDatesInThePlatformFormat() throws IOException {
        byte[] template = template(document -> {
            XWPFTable table = document.createTable(2, 1);
            table.getRow(0).getCell(0).setText("{{#rows}}");
            table.getRow(1).getCell(0).setText("[day]");
        });

        Map<String, Object> data = Map.of("rows", List.of(
                Map.of("day", SIGNED.toLocalDate()),
                Map.of("day", SIGNED)));
        try (XWPFDocument rendered = render(template, data)) {
            XWPFTable table = rendered.getTables().getFirst();
            assertEquals("2026-11-12", table.getRow(1).getCell(0).getText());
            assertEquals("2026-11-12 13:00:40", table.getRow(2).getCell(0).getText());
        }
    }

    private static byte[] template(Consumer<XWPFDocument> content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            content.accept(document);
            document.write(out);
            return out.toByteArray();
        }
    }

    private static XWPFDocument render(byte[] template, Map<String, Object> data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WordFileGenerator.renderDocument(new ByteArrayInputStream(template), data, out);
        return new XWPFDocument(new ByteArrayInputStream(out.toByteArray()));
    }
}
