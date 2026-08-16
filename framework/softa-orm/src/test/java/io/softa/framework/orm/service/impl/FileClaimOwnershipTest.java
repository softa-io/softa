package io.softa.framework.orm.service.impl;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.framework.orm.entity.FileRecord;
import io.softa.framework.orm.service.FileService.FileClaim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Which claims {@code claimFiles} accepts, and — the point of the test — which it refuses.
 *
 * <p>The refusal is the security-relevant half. Writing another row's file id into a row you may edit
 * would otherwise re-point that file at your row, and reading your row would hand you their file: a path
 * that never touches {@code /file/**} and so is not covered by any check living there.
 */
class FileClaimOwnershipTest {

    private static final long OTHER_USER = 7L;

    private FileServiceImpl serviceWith(FileRecord... stored) {
        FileServiceImpl service = spy(new FileServiceImpl());
        doReturn(List.of(stored)).when(service).getByIds(anyList());
        doReturn(true).when(service).updateList(anyList());
        return service;
    }

    private FileRecord record(Long id, String modelName, String rowId, String fieldName) {
        FileRecord record = new FileRecord();
        record.setId(id);
        record.setModelName(modelName);
        record.setRowId(rowId);
        record.setFieldName(fieldName);
        record.setCreatedId(OTHER_USER);
        return record;
    }

    @SuppressWarnings("unchecked")
    private List<FileRecord> capturedUpdate(FileServiceImpl service) {
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(service).updateList(captor.capture());
        return captor.getValue();
    }

    /** The ordinary case: a file nobody has claimed gets bound to the row that just referenced it. */
    @Test
    void unclaimedFileIsBoundToTheClaimingRow() {
        FileServiceImpl service = serviceWith(record(1L, "Employee", null, null));

        service.claimFiles(List.of(new FileClaim(1L, "Employee", "100", "attachment")), List.of());

        List<FileRecord> updated = capturedUpdate(service);
        assertEquals(1, updated.size());
        assertEquals("100", updated.get(0).getRowId());
        assertEquals("Employee", updated.get(0).getModelName());
        assertEquals("attachment", updated.get(0).getFieldName());
    }

    /** A file already owned by another row must not move — this is the theft the claim has to refuse. */
    @Test
    void fileOwnedByAnotherRowIsNotMoved() {
        FileServiceImpl service = serviceWith(record(1L, "Employee", "999", "attachment"));

        service.claimFiles(List.of(new FileClaim(1L, "Employee", "100", "attachment")), List.of());

        verify(service, never()).updateList(anyList());
    }

    /** Same model, same row, same field — nothing changed, so re-saving a row must not churn a write. */
    @Test
    void reSavingTheSameBindingWritesNothing() {
        FileServiceImpl service = serviceWith(record(1L, "Employee", "100", "attachment"));

        service.claimFiles(List.of(new FileClaim(1L, "Employee", "100", "attachment")), List.of());

        verify(service, never()).updateList(anyList());
    }

    /** Owned by another MODEL counts as owned too — the row id alone does not identify a row. */
    @Test
    void fileOwnedByAnotherModelIsNotMoved() {
        FileServiceImpl service = serviceWith(record(1L, "EmpDocument", "100", "attachment"));

        service.claimFiles(List.of(new FileClaim(1L, "Employee", "100", "attachment")), List.of());

        verify(service, never()).updateList(anyList());
    }

    /** A claim naming a file that no longer exists is dropped: the business write already succeeded. */
    @Test
    void claimForAMissingFileIsDropped() {
        FileServiceImpl service = serviceWith();

        service.claimFiles(List.of(new FileClaim(404L, "Employee", "100", "attachment")), List.of());

        verify(service, never()).updateList(anyList());
    }

    /** Nothing to do, and nothing read: an empty claim set must not reach the database at all. */
    @Test
    void emptyClaimsTouchNothing() {
        FileServiceImpl service = spy(new FileServiceImpl());

        service.claimFiles(List.of(), List.of());

        verify(service, never()).getByIds(anyList());
        verify(service, never()).updateList(anyList());
        assertTrue(true);
    }

    /**
     * An unclaimed file still records the model it was uploaded against. A row of a different model
     * must not be able to pull it in: the claimer only proved they may edit their own row, which says
     * nothing about a file someone else is midway through saving somewhere else.
     */
    @Test
    void unclaimedFileIsNotPulledAcrossModels() {
        FileServiceImpl service = serviceWith(record(1L, "Employee", null, null));

        service.claimFiles(List.of(new FileClaim(1L, "Department", "100", "attachment")), List.of());

        verify(service, never()).updateList(anyList());
    }

    /** Same model, no row yet: still open, because uploader and saver are legitimately different people. */
    @Test
    void unclaimedFileIsBoundWhenTheModelMatches() {
        FileServiceImpl service = serviceWith(record(1L, "Employee", null, null));

        service.claimFiles(List.of(new FileClaim(1L, "Employee", "100", "attachment")), List.of());

        assertEquals("100", capturedUpdate(service).getFirst().getRowId());
    }

    /** A file uploaded with no model recorded at all is claimable by anything — nothing to contradict. */
    @Test
    void unclaimedFileWithNoModelIsStillClaimable() {
        FileServiceImpl service = serviceWith(record(1L, null, null, null));

        service.claimFiles(List.of(new FileClaim(1L, "Department", "100", "attachment")), List.of());

        assertEquals("Department", capturedUpdate(service).getFirst().getModelName());
    }
}
