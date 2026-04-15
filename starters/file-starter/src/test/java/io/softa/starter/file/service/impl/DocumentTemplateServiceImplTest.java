package io.softa.starter.file.service.impl;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.dto.UploadFileDTO;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.service.FileService;
import io.softa.starter.file.entity.DocumentTemplate;
import io.softa.starter.file.enums.DocumentTemplateType;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentTemplateServiceImplTest {

    @Test
    void generatePreviewDocumentUploadsPdfPreview() throws Exception {
        FileService fileService = mock(FileService.class);
        AtomicReference<UploadFileDTO> uploadedDto = new AtomicReference<>();
        AtomicReference<byte[]> uploadedBytes = new AtomicReference<>();
        when(fileService.uploadFromStream(any())).thenAnswer(invocation -> {
            UploadFileDTO dto = invocation.getArgument(0);
            uploadedDto.set(dto);
            uploadedBytes.set(dto.getInputStream().readAllBytes());
            FileInfo fileInfo = new FileInfo();
            fileInfo.setFileId(101L);
            return fileInfo;
        });

        DocumentTemplateServiceImpl service = Mockito.spy(new DocumentTemplateServiceImpl());
        ReflectionTestUtils.setField(service, "fileService", fileService);

        FileInfo result = service.generatePreviewDocument("<html><body><h1>Hello {{name}}</h1></body></html>");

        assertEquals(101L, result.getFileId());
        assertNotNull(uploadedDto.get());
        assertEquals("DocumentTemplate", uploadedDto.get().getModelName());
        assertEquals("preview_document", uploadedDto.get().getFileName());
        assertEquals(FileType.PDF, uploadedDto.get().getFileType());
        assertNotNull(uploadedBytes.get());
        assertTrue(uploadedBytes.get().length > 5);
        assertEquals("%PDF-", new String(uploadedBytes.get(), 0, 5, StandardCharsets.US_ASCII));
        verify(fileService).uploadFromStream(any());
    }

    @Test
    void generatePreviewTemplateUsesStoredPdfTemplateBytes() throws Exception {
        FileService fileService = mock(FileService.class);
        byte[] sourcePdfBytes = "%PDF-template-preview".getBytes(StandardCharsets.US_ASCII);
        AtomicReference<UploadFileDTO> uploadedDto = new AtomicReference<>();
        AtomicReference<byte[]> uploadedBytes = new AtomicReference<>();

        FileInfo templateFileInfo = new FileInfo();
        templateFileInfo.setFileId(88L);
        templateFileInfo.setFileName("contract.pdf");
        templateFileInfo.setFileType(FileType.PDF);

        when(fileService.getByFileId(88L)).thenReturn(Optional.of(templateFileInfo));
        when(fileService.downloadStream(88L)).thenReturn(new ByteArrayInputStream(sourcePdfBytes));
        when(fileService.uploadFromStream(any())).thenAnswer(invocation -> {
            UploadFileDTO dto = invocation.getArgument(0);
            uploadedDto.set(dto);
            uploadedBytes.set(dto.getInputStream().readAllBytes());
            FileInfo fileInfo = new FileInfo();
            fileInfo.setFileId(202L);
            return fileInfo;
        });

        DocumentTemplate template = new DocumentTemplate();
        template.setId(1L);
        template.setModelName("SalesOrder");
        template.setFileName("sales_order_contract");
        template.setTemplateType(DocumentTemplateType.PDF);
        template.setFileId(88L);

        DocumentTemplateServiceImpl service = Mockito.spy(new DocumentTemplateServiceImpl());
        ReflectionTestUtils.setField(service, "fileService", fileService);
        doReturn(Optional.of(template)).when(service).getById(1L);

        FileInfo result = service.generatePreviewTemplate(1L);

        assertEquals(202L, result.getFileId());
        assertNotNull(uploadedDto.get());
        assertEquals("SalesOrder", uploadedDto.get().getModelName());
        assertEquals("sales_order_contract", uploadedDto.get().getFileName());
        assertEquals(FileType.PDF, uploadedDto.get().getFileType());
        assertArrayEquals(sourcePdfBytes, uploadedBytes.get());
        verify(fileService).getByFileId(88L);
        verify(fileService).downloadStream(88L);
        verify(fileService).uploadFromStream(any());
    }
}
