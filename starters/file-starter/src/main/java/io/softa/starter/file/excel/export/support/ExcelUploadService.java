package io.softa.starter.file.excel.export.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.BaseException;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.dto.UploadFileDTO;
import io.softa.framework.orm.enums.FileSource;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.service.FileService;
import io.softa.starter.file.excel.export.ExcelSheetData;

@Component
public class ExcelUploadService {

    @Autowired
    private FileService fileService;

    @Autowired
    private ExcelWriterFactory excelWriterFactory;

    public FileInfo generateFileAndUpload(String modelName, String fileName, ExcelSheetData sheetData) {
        return this.generateFileAndUpload(modelName, fileName, List.of(sheetData));
    }

    /**
     * Generate an Excel workbook with one or more sheets and upload it to file storage.
     */
    public FileInfo generateFileAndUpload(String modelName, String fileName, List<ExcelSheetData> sheetDataList) {
        try {
            return this.uploadExcelBytes(modelName, fileName, this.writeWorkbook(sheetDataList));
        } catch (BaseException e) {
            // Already carries a diagnosis — a permission refusal, a missing model, a storage failure.
            // Wrapping it in "error generating Excel with the provided data" blames the data for
            // something that has nothing to do with it, which is exactly how a denied FileRecord insert
            // reads as a broken template.
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Error generating Excel {0} with the provided data.", fileName, e);
        }
    }

    /**
     * The workbook's bytes, one sheet per entry in order — what {@link #generateFileAndUpload} uploads.
     * Separate so a workbook can be read back as it will be stored, without storage.
     */
    public byte[] writeWorkbook(List<ExcelSheetData> sheetDataList) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ExcelWriter excelWriter = FesodSheet.write(outputStream).build()) {
            for (int i = 0; i < sheetDataList.size(); i++) {
                ExcelSheetData sheetData = sheetDataList.get(i);
                List<List<String>> headersList = sheetData.getHeaders().stream()
                        .map(Collections::singletonList).toList();
                WriteSheet writeSheet = excelWriterFactory
                        .createSheetBuilder(i, safeSheetName(sheetData.getSheetName()), headersList,
                                sheetData.getWriteHandlers())
                        .build();
                excelWriter.write(sheetData.getRowsTable(), writeSheet);
            }
            excelWriter.finish();
            return outputStream.toByteArray();
        }
    }

    /**
     * The name Excel will actually store for a sheet.
     *
     * <p>Excel caps a sheet name at 31 characters and rejects {@code []:*?/\\}, so a longer or
     * dirtier name is not kept as written — it is silently altered on the way in. Anything that later
     * looks a sheet up by the name it asked for then misses, which is how a workbook ends up with the
     * data written but nothing attached to it. Every writer funnels through here, so the name is made
     * safe once, in the one place that knows a workbook is being built.
     */
    public static String safeSheetName(String sheetName) {
        return StringUtils.isBlank(sheetName) ? sheetName : WorkbookUtil.createSafeSheetName(sheetName);
    }

    /**
     * Upload Excel bytes and return file info with download URL.
     */
    public FileInfo uploadExcelBytes(String modelName, String fileName, byte[] excelBytes) {
        try (InputStream resultStream = new ByteArrayInputStream(excelBytes)) {
            UploadFileDTO uploadFileDTO = new UploadFileDTO();
            uploadFileDTO.setModelName(modelName);
            uploadFileDTO.setFileName(fileName);
            uploadFileDTO.setFileType(FileType.XLSX);
            uploadFileDTO.setFileSize(excelBytes.length / 1024);
            uploadFileDTO.setFileSource(FileSource.DOWNLOAD);
            uploadFileDTO.setInputStream(resultStream);
            return fileService.uploadFromStream(uploadFileDTO);
        } catch (IOException e) {
            throw new BusinessException("Error uploading Excel stream", e);
        }
    }
}
