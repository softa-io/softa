package io.softa.framework.orm.service.impl;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.entity.FileRecord;
import io.softa.framework.orm.oss.OssClientService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Copying a row that carries an attachment has to give the copy its own file record. Two rows sharing
 * one record leaves the copy readable only through the original's permissions — the case that breaks
 * when a pre-boarding attachment becomes an employee's and HR, who can read the employee but not the
 * pre-boarding row, is handed nothing.
 *
 * <p>And its own stored object: {@code oss_key} is unique, so a copy that reused the source's key was
 * refused by the database — every hire carrying a candidate's photo or attachment failed with it.
 */
class FileCopyTest {

    private FileRecord source() {
        FileRecord record = new FileRecord();
        record.setId(1L);
        record.setOssKey("tenant/EmpPreAttachment/passport.pdf");
        record.setFileName("passport.pdf");
        record.setChecksum("abc123");
        record.setFileSize(42);
        record.setModelName("EmpPreAttachment");
        record.setRowId("500");
        record.setFieldName("attachmentFileId");
        return record;
    }

    private final OssClientService oss = mock(OssClientService.class);

    private FileServiceImpl service() {
        FileServiceImpl service = spy(new FileServiceImpl());
        ReflectionTestUtils.setField(service, "ossClientService", oss);
        doReturn("tenant/EmpAttachment/new.pdf").when(service).generateOssKey("EmpAttachment", "passport.pdf");
        return service;
    }

    @Test
    void theCopyOwnsItsOwnRecordAndItsOwnStoredObject() {
        FileServiceImpl service = service();
        doReturn(Optional.of(source())).when(service).getById(1L);
        doReturn(9L).when(service).createOne(any(FileRecord.class));

        Optional<Long> copied = service.copyFileTo(1L, "EmpAttachment", 700L, "attachmentFileId");

        assertEquals(9L, copied.orElseThrow());
        verify(oss).copyObject("tenant/EmpPreAttachment/passport.pdf", "tenant/EmpAttachment/new.pdf", "passport.pdf");
        ArgumentCaptor<FileRecord> written = ArgumentCaptor.forClass(FileRecord.class);
        verify(service).createOne(written.capture());
        FileRecord copy = written.getValue();
        assertEquals("tenant/EmpAttachment/new.pdf", copy.getOssKey(), "a key of its own, under the new owner's model");
        assertEquals("abc123", copy.getChecksum(), "the same bytes");
        assertEquals("passport.pdf", copy.getFileName());
        assertEquals("EmpAttachment", copy.getModelName(), "the copy is owned by the new row");
        assertEquals("700", copy.getRowId());
        assertNull(copy.getId(), "a copy is a new record, not an overwrite of the source");
    }

    @Test
    void aRecordWithNoObjectYetIsCopiedWithoutOne() {
        FileServiceImpl service = service();
        FileRecord source = source();
        source.setOssKey(null);
        doReturn(Optional.of(source)).when(service).getById(1L);
        doReturn(9L).when(service).createOne(any(FileRecord.class));

        service.copyFileTo(1L, "EmpAttachment", 700L, "attachmentFileId");

        verify(oss, never()).copyObject(anyString(), anyString(), anyString());
        ArgumentCaptor<FileRecord> written = ArgumentCaptor.forClass(FileRecord.class);
        verify(service).createOne(written.capture());
        assertNull(written.getValue().getOssKey());
    }

    @Test
    void aMissingSourceCopiesNothing() {
        FileServiceImpl service = service();
        doReturn(Optional.empty()).when(service).getById(404L);

        assertTrue(service.copyFileTo(404L, "EmpAttachment", 700L, "attachmentFileId").isEmpty());
        verify(oss, never()).copyObject(anyString(), anyString(), anyString());
    }
}
