package io.softa.starter.flow.controller;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.starter.flow.runtime.engine.InProcessFlowClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The current user's own flow lists must not be subject to row scope, and nothing the instance access
 * guard rules on may be waived.
 *
 * <p>The flow models are anchorless and no business role is granted them, so row scope fails closed to
 * no rows. Left on, an approver's inbox, an initiator's requests and a user's delegations all come back
 * empty — silently, which reads as "nothing to do" rather than as a failure. The self-service entry
 * points pin their rows to the caller, so they carry {@link SkipPermissionCheck}.
 *
 * <p>The per-instance reads must not carry it. The guard admits a caller who is neither the initiator
 * nor a task holder by asking whether their own permissions reach the instance or its record; a waiver
 * around the guard would answer yes for everyone.
 */
class FlowSelfServiceScopeWaiverTest {

    @Test
    @DisplayName("every self-service entry point waives row scope")
    void selfServiceEntryPointsWaiveRowScope() {
        assertWaived(FlowApprovalTaskController.class, "counts", "searchTasks");
        assertWaived(FlowDelegationController.class, "searchDelegations", "delegableFlows", "cancel");
        assertWaived(FlowInstanceController.class, "searchInstances");
    }

    @Test
    @DisplayName("the reads the access guard rules on stay checked")
    void guardedReadsStayChecked() {
        assertChecked(FlowApprovalTaskController.class, "getTasksByInstance");
        assertChecked(FlowApprovalRecordController.class, "getByInstance");
        assertChecked(FlowInstanceController.class, "getInstance", "getOverlay", "getTrace",
                "getRecordApprovals", "previewRecordApproval", "getInstanceRecord", "getRecordThroughApprovals",
                "getInstanceSummary", "getInstanceDesign", "getFormPermissions");
        assertChecked(InProcessFlowClient.class, "recordApprovals", "previewRecord");
    }

    @Test
    @DisplayName("a person's directory search keeps their row scope; the rest of the directory waives it")
    void theDirectorySearchKeepsTheCallersRowScope() {
        for (Method method : declared(FlowDirectoryController.class, "searchPeople")) {
            assertNull(method.getAnnotation(SkipPermissionCheck.class), "FlowDirectoryController.searchPeople reads "
                    + "the directory model with the caller's row scope, which decides whom they may pick; a waiver "
                    + "would hand every person the whole tenant.");
        }
        assertWaived(FlowDirectoryController.class, "getPeople", "searchAllPeople", "getAllPeople", "searchDepartments",
                "getDepartments");
    }

    private static void assertWaived(Class<?> type, String... names) {
        for (String name : names) {
            for (Method method : declared(type, name)) {
                assertNotNull(method.getAnnotation(SkipPermissionCheck.class),
                        type.getSimpleName() + "." + name + " pins its rows to the current user and must carry "
                                + "@SkipPermissionCheck — the flow models are granted to no business role, so row "
                                + "scope fails closed and the list goes silently empty.");
            }
        }
    }

    private static void assertChecked(Class<?> type, String... names) {
        for (String name : names) {
            for (Method method : declared(type, name)) {
                assertNull(method.getAnnotation(SkipPermissionCheck.class),
                        type.getSimpleName() + "." + name + " is ruled on by FlowInstanceAccessGuard, which judges "
                                + "callers other than the initiator and the task holders by their own permissions; "
                                + "a waiver here would admit everyone.");
            }
        }
    }

    private static List<Method> declared(Class<?> type, String name) {
        List<Method> methods = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name) && !method.isSynthetic())
                .toList();
        assertFalse(methods.isEmpty(), type.getSimpleName() + " declares no method " + name);
        return methods;
    }
}
