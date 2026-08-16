package io.softa.framework.orm.service.impl;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.entity.FileRecord;
import io.softa.framework.orm.service.FileService.FileClaim;
import io.softa.framework.orm.service.FileService.FileSlot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Clearing a file field has to release the file, or the record keeps pointing at the row: getRowFiles
 * goes on listing it and everyone who can read the row goes on reading it — the attachment surviving
 * its own removal. The release is scoped to the fields the write actually carried, which is what keeps
 * a partial update from unclaiming everything the row still holds.
 */
class FileSlotReleaseTest {

    private FileRecord bound(Long id, String field) {
        FileRecord record = new FileRecord();
        record.setId(id);
        record.setModelName("Employee");
        record.setRowId("100");
        record.setFieldName(field);
        return record;
    }

    /** A file still bound to a carried field but absent from the new value goes back to unclaimed. */
    @Test
    void aFileTheFieldNoLongerHoldsIsReleased() {
        FileServiceImpl service = spy(new FileServiceImpl());
        FileRecord stale = bound(7L, "attachment");
        doReturn(List.of(stale)).when(service).searchList(any(Filters.class));
        AtomicReference<List<FileRecord>> written = new AtomicReference<>();
        doAnswer(inv -> {
            written.set(inv.getArgument(0));
            return true;
        }).when(service).updateList(anyList());

        Context ctx = new Context();
        ContextHolder.runWith(ctx, () -> service.claimFiles(
                List.of(), List.of(new FileSlot("Employee", "100", "attachment"))));

        assertEquals(1, written.get().size());
        FileRecord released = written.get().getFirst();
        assertNull(released.getRowId(), "the binding is cleared");
        assertNull(released.getFieldName());
        assertEquals("Employee", released.getModelName(),
                "the model it was uploaded against survives — clearing it would make the file "
                        + "claimable by a row of any model, so removing an attachment would widen it");
        assertEquals(7L, released.getId(), "the record itself survives — only its binding is dropped");
    }

    /** The file the write still references keeps its binding; nothing is churned. */
    @Test
    void aFileTheFieldStillHoldsIsKept() {
        FileServiceImpl service = spy(new FileServiceImpl());
        doReturn(List.of(bound(7L, "attachment"))).when(service).searchList(any(Filters.class));
        doReturn(List.of(bound(7L, "attachment"))).when(service).getByIds(anyList());

        Context ctx = new Context();
        ContextHolder.runWith(ctx, () -> service.claimFiles(
                List.of(new FileClaim(7L, "Employee", "100", "attachment")),
                List.of(new FileSlot("Employee", "100", "attachment"))));

        // Already bound exactly this way: no release, and no re-write of an unchanged binding.
        verify(service, never()).updateList(anyList());
    }

    /** No slots means the write spoke for no file field — releasing anything would be inventing intent. */
    @Test
    void noSlotsReleasesNothing() {
        FileServiceImpl service = spy(new FileServiceImpl());

        Context ctx = new Context();
        ContextHolder.runWith(ctx, () -> service.claimFiles(List.of(), List.of()));

        verify(service, never()).searchList(any(Filters.class));
        verify(service, never()).updateList(anyList());
    }

    /** The release reads and writes past FileRecord's own (matchNone) scope, like every other path. */
    @Test
    void releaseRunsWithScopeWaived() {
        FileServiceImpl service = spy(new FileServiceImpl());
        AtomicReference<Boolean> skipped = new AtomicReference<>(false);
        doAnswer(inv -> {
            skipped.set(ContextHolder.getContext().isSkipPermissionCheck());
            return List.<FileRecord>of();
        }).when(service).searchList(any(Filters.class));

        Context ctx = new Context();
        ctx.setSkipPermissionCheck(false);
        ContextHolder.runWith(ctx, () -> service.claimFiles(
                List.of(), List.of(new FileSlot("Employee", "100", "attachment"))));

        assertTrue(skipped.get(), "the release must read past FileRecord's own scope");
    }
}
