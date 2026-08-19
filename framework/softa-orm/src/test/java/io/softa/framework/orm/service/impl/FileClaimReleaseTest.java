package io.softa.framework.orm.service.impl;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.entity.FileRecord;
import io.softa.framework.orm.service.FileService.FileClaim;
import io.softa.framework.orm.service.FileService.FileSlot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Claim writes the binding that {@code assertClaimable} reads back — without it no file is ever owned,
 * every file looks unclaimed, and the write guard has nothing to enforce. Release is the other half:
 * a cleared attachment must stop being reachable through the row it used to hang on.
 */
class FileClaimReleaseTest {

    private static FileRecord record(Long id, String rowId, String fieldName) {
        FileRecord record = new FileRecord();
        record.setId(id);
        record.setModelName("EmpAttachment");
        record.setRowId(rowId);
        record.setFieldName(fieldName);
        return record;
    }

    private static void run(FileServiceImpl service, List<FileClaim> claims, List<FileSlot> slots) {
        ContextHolder.runWith(new Context(), () -> service.claimFiles(claims, slots));
    }

    @Test
    void anUnclaimedFileGetsBoundToTheRowThatReferencesIt() {
        FileServiceImpl service = spy(new FileServiceImpl());
        doReturn(List.of(record(9L, null, null))).when(service).getByIds(anyList());
        doReturn(List.of()).when(service).searchList(any(Filters.class));
        ArgumentCaptor<List<FileRecord>> written = ArgumentCaptor.captor();
        doReturn(true).when(service).updateList(anyList());

        run(service, List.of(new FileClaim(9L, "EmpAttachment", "5", "attachment")),
                List.of(new FileSlot("EmpAttachment", "5", "attachment")));

        verify(service).updateList(written.capture());
        FileRecord bound = written.getValue().getFirst();
        assertEquals("5", bound.getRowId(), "the row that referenced it now owns it");
        assertEquals("attachment", bound.getFieldName());
    }

    /** Re-saving an unchanged row must not churn writes. */
    @Test
    void aFileAlreadyBoundToThisRowIsNotRewritten() {
        FileServiceImpl service = spy(new FileServiceImpl());
        doReturn(List.of(record(9L, "5", "attachment"))).when(service).getByIds(anyList());
        doReturn(List.of(record(9L, "5", "attachment"))).when(service).searchList(any(Filters.class));

        run(service, List.of(new FileClaim(9L, "EmpAttachment", "5", "attachment")),
                List.of(new FileSlot("EmpAttachment", "5", "attachment")));

        verify(service, never()).updateList(anyList());
    }

    /**
     * Clearing an attachment releases it. Without this the record kept pointing at the row, so
     * getRowFiles went on listing it and everyone who could read the row could still read it — the file
     * surviving its own removal.
     */
    @Test
    void aFileTheWriteStoppedReferencingIsReleased() {
        FileServiceImpl service = spy(new FileServiceImpl());
        doReturn(List.of(record(9L, "5", "attachment"))).when(service).searchList(any(Filters.class));
        ArgumentCaptor<List<FileRecord>> written = ArgumentCaptor.captor();
        doReturn(true).when(service).updateList(anyList(), eq(false));

        // The write carried the field, and carried no ids in it — the field is now empty.
        run(service, List.of(), List.of(new FileSlot("EmpAttachment", "5", "attachment")));

        verify(service).updateList(written.capture(), eq(false));
        FileRecord released = written.getValue().getFirst();
        assertNull(released.getRowId(), "the binding goes");
        assertNull(released.getFieldName());
        assertEquals("EmpAttachment", released.getModelName(),
                "the model it was uploaded against stays — clearing it would make the file claimable "
                        + "by a row of ANY model, widening exposure on removal");
        assertEquals(9L, released.getId(), "the record itself survives; only its binding is dropped");
    }

    /**
     * A release is the writing of nulls, so it has to use the overload that writes them. The
     * one-argument updateList ignores nulls — it would issue an update that changed nothing and leave
     * every released file bound to its old row, while every assertion on the object handed to the write
     * still passed. So the call itself is what gets pinned.
     */
    @Test
    void theReleaseWritesNullsRatherThanIgnoringThem() {
        FileServiceImpl service = spy(new FileServiceImpl());
        doReturn(List.of(record(9L, "5", "attachment"))).when(service).searchList(any(Filters.class));
        doReturn(true).when(service).updateList(anyList(), eq(false));

        run(service, List.of(), List.of(new FileSlot("EmpAttachment", "5", "attachment")));

        verify(service).updateList(anyList(), eq(false));
        verify(service, never()).updateList(anyList());
    }

    /** A field the write never mentioned makes no statement, so a partial update unclaims nothing. */
    @Test
    void aWriteCarryingNoFileFieldReleasesNothing() {
        FileServiceImpl service = spy(new FileServiceImpl());

        run(service, List.of(), List.of());

        verify(service, never()).searchList(any(Filters.class));
        verify(service, never()).updateList(anyList(), eq(false));
    }
}
