package io.softa.framework.orm.service.impl;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.entity.FileRecord;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * {@code assertClaimable} is the single point where "which row owns this file" is enforced, and the
 * expansion path is built on its answer: a File column is turned into a download URL with row-scope
 * waived, on the grounds that reading the row was authorization enough. That only holds if the id in
 * the column got there legitimately — so every case below is really a statement about what the
 * expansion is allowed to trust.
 *
 * <p>The attack it exists to stop: write a stranger's file id into a row you may edit, read the row
 * back, and let the expansion hand you their file. Nothing else in the ORM would refuse that — a File
 * field is an ordinary column.
 */
class FileClaimableTest {

    private static FileRecord record(Long id, String modelName, String rowId) {
        FileRecord record = new FileRecord();
        record.setId(id);
        record.setModelName(modelName);
        record.setRowId(rowId);
        return record;
    }

    private static void assertClaimable(FileServiceImpl service, String modelName, String rowId) {
        Context ctx = new Context();
        ctx.setUserId(7L);
        ContextHolder.runWith(ctx, () ->
                service.assertClaimable(modelName, rowId, "attachment", List.of(9L)));
    }

    private static FileServiceImpl serviceReturning(FileRecord... records) {
        FileServiceImpl service = spy(new FileServiceImpl());
        doReturn(List.of(records)).when(service).getByIds(anyList());
        return service;
    }

    /** The create-form path: uploaded before the row existed, so the row it will hang on is unknown. */
    @Test
    void anUnclaimedFileOfTheSameModelMayBeAttached() {
        assertClaimable(serviceReturning(record(9L, "EmpAttachment", null)), "EmpAttachment", "5");
    }

    /** Re-saving a record must not fail on the file it already holds. */
    @Test
    void theRowsOwnFileMayBeAttachedAgain() {
        assertClaimable(serviceReturning(record(9L, "EmpAttachment", "5")), "EmpAttachment", "5");
    }

    /**
     * The core case. The file belongs to someone else's row; writing its id into a row the caller may
     * edit is what would turn "I can edit my own record" into "I can read your document".
     */
    @Test
    void aFileOwnedByAnotherRowIsRefused() {
        FileServiceImpl service = serviceReturning(record(9L, "EmpAttachment", "999"));
        assertThrows(PermissionException.class, () -> assertClaimable(service, "EmpAttachment", "5"));
    }

    /**
     * Unclaimed is not the same as free. An upload always records the model it was made against, so a
     * file destined for an Employee cannot be pulled into a row of some other model — a claim writes an
     * id into a field the claimer may edit, and nothing else about that write says whose file it was.
     */
    @Test
    void anUnclaimedFileOfAnotherModelIsRefused() {
        FileServiceImpl service = serviceReturning(record(9L, "Employee", null));
        assertThrows(PermissionException.class, () -> assertClaimable(service, "EmpAttachment", "5"));
    }

    /** A create carries no row id yet, so only the unclaimed case can pass — never someone else's. */
    @Test
    void aCreateCannotAttachAFileAlreadyOwnedBySomeRow() {
        FileServiceImpl service = serviceReturning(record(9L, "EmpAttachment", "999"));
        assertThrows(PermissionException.class, () -> assertClaimable(service, "EmpAttachment", null));
    }

    /**
     * A dangling id fails rather than being dropped: silently saving a row whose attachment vanished
     * looks to the user exactly like saving one that worked, until they come back for the file.
     */
    @Test
    void anIdNamingNoRecordIsRefused() {
        FileServiceImpl service = serviceReturning();
        assertThrows(PermissionException.class, () -> assertClaimable(service, "EmpAttachment", "5"));
    }
}
