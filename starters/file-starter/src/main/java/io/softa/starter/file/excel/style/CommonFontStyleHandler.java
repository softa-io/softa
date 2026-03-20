package io.softa.starter.file.excel.style;

import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;
import org.apache.fesod.sheet.write.metadata.style.WriteCellStyle;
import org.apache.fesod.sheet.write.metadata.style.WriteFont;

import io.softa.starter.file.constant.FileConstant;

/**
 * Normalize generated cell fonts across Excel export paths.
 */
public class CommonFontStyleHandler implements CellWriteHandler {

    @Override
    public void afterCellDispose(CellWriteHandlerContext context) {
        WriteCellStyle writeCellStyle = context.getFirstCellData().getOrCreateStyle();
        WriteFont writeFont = new WriteFont();
        WriteFont.merge(writeCellStyle.getWriteFont(), writeFont);
        writeFont.setFontName(FileConstant.DEFAULT_EXCEL_FONT_NAME);
        if (context.getHead()) {
            writeFont.setFontHeightInPoints(FileConstant.DEFAULT_EXCEL_HEAD_FONT_SIZE);
            writeFont.setColor(FileConstant.DEFAULT_EXCEL_HEAD_FONT_COLOR);
        } else {
            writeFont.setFontHeightInPoints(FileConstant.DEFAULT_EXCEL_BODY_FONT_SIZE);
        }
        writeCellStyle.setWriteFont(writeFont);
    }
}
