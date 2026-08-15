package io.softa.framework.orm.service.impl;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.entity.FileRecord;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.PermissionService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Row access is not field access. {@code getRowFiles} authorizes the owning row and then lists every
 * file hanging on it — so without this, a document attached to a field behind a sensitive field set
 * comes back in full to a caller whose view of that same field was masked.
 */
class RowFilesFieldMaskTest {

    private FileRecord record(Long id, String fieldName) {
        FileRecord record = new FileRecord();
        record.setId(id);
        record.setModelName("Employee");
        record.setRowId("100");
        record.setFieldName(fieldName);
        return record;
    }

    private List<FileRecord> stored = List.of();

    private FileServiceImpl serviceWith(Set<String> blocked, FileRecord... stored) {
        FileServiceImpl service = spy(new FileServiceImpl());
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.getUserBlockedModelFields("Employee", AccessType.READ)).thenReturn(blocked);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        this.stored = List.of(stored);
        return service;
    }

    @Test
    void aFileOnAMaskedFieldIsNotListed() {
        FileServiceImpl service = serviceWith(Set.of("bankAccountProof"),
                record(1L, "attachment"), record(2L, "bankAccountProof"));

        List<FileRecord> readable = service.readableFiles("Employee", stored);

        assertEquals(List.of(1L), readable.stream().map(FileRecord::getId).toList());
    }

    /** A file recorded against no field belongs to the row itself, so no field mask can speak for it. */
    @Test
    void aFileOnNoFieldSurvivesTheMask() {
        FileServiceImpl service = serviceWith(Set.of("bankAccountProof"), record(3L, null));

        assertEquals(List.of(3L), service.readableFiles("Employee", stored).stream()
                .map(FileRecord::getId).toList());
    }

    @Test
    void nothingBlockedListsEverything() {
        FileServiceImpl service = serviceWith(Set.of(),
                record(1L, "attachment"), record(2L, "bankAccountProof"));

        assertEquals(2, service.readableFiles("Employee", stored).size());
    }
}
