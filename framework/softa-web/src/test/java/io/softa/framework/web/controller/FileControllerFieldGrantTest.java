package io.softa.framework.web.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.RecordFieldEditGrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A file uploaded to a field of an existing record. The caller's permissions on the model decide — unless a grant
 * lets them write that field anyway: an approver whose step lets them edit the record's attachments has no change
 * scope on the record of their own, and was refused the file the approval would write back.
 */
class FileControllerFieldGrantTest {

    private static final String CLAIM = "ExpenseClaim";

    private final FileService files = mock(FileService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final RecordFieldEditGrant approvalStep = mock(RecordFieldEditGrant.class);
    private final FileController controller = new FileController();
    private final MockMultipartFile receipt = new MockMultipartFile("file", "receipt.png", "image/png", new byte[]{1});

    FileControllerFieldGrantTest() {
        ReflectionTestUtils.setField(controller, "service", files);
        ReflectionTestUtils.setField(controller, "permissionService", permissions);
        ReflectionTestUtils.setField(controller, "fieldEditGrants", List.of(approvalStep));
        when(files.uploadFile(eq(CLAIM), any(), any(), any())).thenReturn(new FileInfo());
    }

    @Test
    void aFieldTheCallerIsGrantedByTheirApprovalStep_takesTheFile_withoutTheirOwnChangeScope() {
        when(approvalStep.grants(CLAIM, 42L, "attachments")).thenReturn(true);

        assertThat(controller.uploadFileToField(CLAIM, 42L, "attachments", receipt).getData()).isNotNull();

        verify(permissions, never()).hasModelActionGrant(any(), any());
        verify(files).uploadFile(CLAIM, 42L, "attachments", receipt);
    }

    @Test
    void aFieldNoGrantCovers_isAskedOfTheCallersOwnPermissions_asBefore() {
        when(permissions.hasModelActionGrant(CLAIM, AccessType.UPDATE)).thenReturn(false);

        assertThatThrownBy(() -> controller.uploadFileToField(CLAIM, 42L, "startDateNote", receipt))
                .isInstanceOf(PermissionException.class);
        verify(files, never()).uploadFile(any(), any(), any(), any());
    }

    @Test
    void withoutAnyGrant_theCallersPermissionsDecideAlone() {
        ReflectionTestUtils.setField(controller, "fieldEditGrants", null);
        when(permissions.hasModelActionGrant(CLAIM, AccessType.UPDATE)).thenReturn(false);

        assertThatThrownBy(() -> controller.uploadFileToField(CLAIM, 42L, "attachments", receipt))
                .isInstanceOf(PermissionException.class);
    }
}
