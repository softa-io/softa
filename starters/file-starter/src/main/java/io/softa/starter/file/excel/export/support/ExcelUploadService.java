package io.softa.starter.file.excel.export.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
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
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ExcelWriter excelWriter = FesodSheet.write(outputStream).build()) {
            for (int i = 0; i < sheetDataList.size(); i++) {
                ExcelSheetData sheetData = sheetDataList.get(i);
                List<List<String>> headersList = sheetData.getHeaders().stream()
                        .map(Collections::singletonList).toList();
                WriteSheet writeSheet = excelWriterFactory
                        .createSheetBuilder(i, sheetData.getSheetName(), headersList, sheetData.getWriteHandlers())
                        .build();
                excelWriter.write(sheetData.getRowsTable(), writeSheet);
            }
            excelWriter.finish();
            return this.uploadExcelBytes(modelName, fileName, outputStream.toByteArray());
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
