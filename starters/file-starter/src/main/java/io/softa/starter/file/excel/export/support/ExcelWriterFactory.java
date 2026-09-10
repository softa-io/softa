package io.softa.starter.file.excel.export.support;

import java.util.List;

import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.builder.ExcelWriterSheetBuilder;
import org.apache.fesod.sheet.write.handler.WriteHandler;
import org.springframework.stereotype.Component;

import io.softa.starter.file.excel.style.CommonFontStyleHandler;
import io.softa.starter.file.excel.style.CommonHeadStyleHandler;
import io.softa.starter.file.excel.style.CommonSheetStyleHandler;

@Component
public class ExcelWriterFactory {

    /**
     * Create a sheet builder with all shared style handlers registered.
     */
    public ExcelWriterSheetBuilder createSheetBuilder(Integer sheetNo, String sheetName, List<List<String>> headersList,
                                                      WriteHandler... handlers) {
        ExcelWriterSheetBuilder builder = sheetNo == null
                ? FesodSheet.writerSheet(sheetName).head(headersList)
                : FesodSheet.writerSheet(sheetNo, sheetName).head(headersList);
        builder = builder.registerWriteHandler(new CommonSheetStyleHandler());
        builder = builder.registerWriteHandler(new CommonFontStyleHandler());
        builder = builder.registerWriteHandler(new CommonHeadStyleHandler());
        builder = builder.registerWriteHandler(new LargeIntegerAsTextHandler());
        if (handlers == null) {
            return builder;
        }
        for (WriteHandler handler : handlers) {
            if (handler != null) {
                builder = builder.registerWriteHandler(handler);
            }
        }
        return builder;
    }
}
