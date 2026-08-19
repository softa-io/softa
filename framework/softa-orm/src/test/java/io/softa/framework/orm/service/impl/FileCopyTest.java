package io.softa.framework.orm.service.impl;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.orm.entity.FileRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Copying a row that carries an attachment has to give the copy its own file record. Two rows sharing
 * one record leaves the copy readable only through the original's permissions — the case that breaks
 * when a pre-boarding attachment becomes an employee's and HR, who can read the employee but not the
 * pre-boarding row, is handed nothing.
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

    @Test
    void theCopyOwnsItsOwnRecordAndSharesTheStoredObject() {
        FileServiceImpl service = spy(new FileServiceImpl());
        doReturn(Optional.of(source())).when(service).getById(1L);
        doReturn(9L).when(service).createOne(org.mockito.ArgumentMatchers.any(FileRecord.class));

        Optional<Long> copied = service.copyFileTo(1L, "EmpAttachment", 700L, "attachmentFileId");

        assertEquals(9L, copied.orElseThrow());
        ArgumentCaptor<FileRecord> written = ArgumentCaptor.forClass(FileRecord.class);
        verify(service).createOne(written.capture());
        FileRecord copy = written.getValue();
        assertEquals("tenant/EmpPreAttachment/passport.pdf", copy.getOssKey(), "the blob is shared");
        assertEquals("abc123", copy.getChecksum());
        assertEquals("EmpAttachment", copy.getModelName(), "the copy is owned by the new row");
        assertEquals("700", copy.getRowId());
        assertNull(copy.getId(), "a copy is a new record, not an overwrite of the source");
    }

    @Test
    void aMissingSourceCopiesNothing() {
        FileServiceImpl service = spy(new FileServiceImpl());
        doReturn(Optional.empty()).when(service).getById(404L);

        assertTrue(service.copyFileTo(404L, "EmpAttachment", 700L, "attachmentFileId").isEmpty());
    }
}
