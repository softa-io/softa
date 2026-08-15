package io.softa.framework.orm.service.impl;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.entity.FileRecord;
import io.softa.framework.orm.service.FileService.FileOwner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * FileRecord is anchorless, so its own row-scope collapses to matchNone() for a non-admin and a plain
 * getById on it throws before the owning row is ever known. These pin the fix: the FileRecord read runs
 * with permission scope waived, and the waiver is undone afterwards. Authorization has already happened
 * (or is about to) against the owning business row — not against FileRecord — so this is removing an
 * obstacle, not a control.
 */
class FileRecordScopeBypassTest {

    private FileRecord record() {
        FileRecord record = new FileRecord();
        record.setId(1L);
        record.setModelName("Employee");
        record.setRowId("100");
        record.setCreatedId(42L);
        return record;
    }

    /** getFileOwner exists to find the owning row; it must not be gated on FileRecord's own scope. */
    @Test
    void getFileOwnerReadsWithScopeWaivedThenRestores() {
        FileServiceImpl service = spy(new FileServiceImpl());
        AtomicBoolean skippedDuringRead = new AtomicBoolean(false);
        doAnswer(inv -> {
            skippedDuringRead.set(ContextHolder.getContext().isSkipPermissionCheck());
            return Optional.of(record());
        }).when(service).getById(1L);

        Context ctx = new Context();
        ctx.setSkipPermissionCheck(false);
        Optional<FileOwner> owner = ContextHolder.callWith(ctx, () -> service.getFileOwner(1L));

        assertTrue(skippedDuringRead.get(), "FileRecord must be read with its own scope waived");
        assertFalse(ctx.isSkipPermissionCheck(), "the waiver must be undone after the read");
        assertEquals("100", owner.orElseThrow().rowId());
        assertEquals("Employee", owner.orElseThrow().modelName());
    }

    /** A pre-existing skip must be left exactly as it was, not force-reset to false. */
    @Test
    void getFileOwnerRestoresAPreExistingSkip() {
        FileServiceImpl service = spy(new FileServiceImpl());
        doAnswer(inv -> Optional.of(record())).when(service).getById(1L);

        Context ctx = new Context();
        ctx.setSkipPermissionCheck(true);
        ContextHolder.callWith(ctx, () -> service.getFileOwner(1L));

        assertTrue(ctx.isSkipPermissionCheck(), "an already-set skip must survive the call");
    }
}
