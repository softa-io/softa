package io.softa.starter.es.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.PermissionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * A change log entry shows a field only where the caller may see it on that record — neither the new
 * value nor the one it replaced.
 */
class ChangeLogMaskTest {

    private static ChangeLog update(String rowId, Map<String, Object> before, Map<String, Object> after) {
        ChangeLog log = new ChangeLog();
        log.setModel("Employee");
        log.setRowId(rowId);
        log.setAccessType(AccessType.UPDATE);
        log.setDataBeforeChange(new HashMap<>(before));
        log.setDataAfterChange(new HashMap<>(after));
        return log;
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }

    @Test
    void aFieldHiddenOnTheRecordIsRemovedFromBothSides() {
        PermissionService permissions = mock(PermissionService.class);
        // Record 2 hides salary; record 1 shows it.
        doAnswer(inv -> {
            List<Map<String, Object>> rows = inv.getArgument(1);
            rows.forEach(row -> {
                if ("2".equals(String.valueOf(row.get("id")))) row.put("salary", null);
            });
            return null;
        }).when(permissions).maskRows(eq("Employee"), anyList());
        ChangeLogServiceImpl service = new ChangeLogServiceImpl();
        ReflectionTestUtils.setField(service, "permissionService", permissions);

        ChangeLog shown = update("1", values("salary", 100, "name", "A"), values("salary", 200, "name", "B"));
        // A clear: the old value is the one that must not leak.
        ChangeLog hidden = update("2", values("salary", 300, "name", "C"), values("salary", null, "name", "D"));

        ReflectionTestUtils.invokeMethod(service, "maskInaccessibleFields", List.of(shown, hidden));

        assertThat(shown.getDataBeforeChange()).containsEntry("salary", 100);
        assertThat(shown.getDataAfterChange()).containsEntry("salary", 200);
        assertThat(hidden.getDataBeforeChange()).doesNotContainKey("salary").containsEntry("name", "C");
        assertThat(hidden.getDataAfterChange()).doesNotContainKey("salary").containsEntry("name", "D");
    }

    @Test
    void everyReadPathMasksPerRecordBeforeDroppingBlockedFields() {
        PermissionService permissions = mock(PermissionService.class);
        doAnswer(inv -> {
            List<Map<String, Object>> rows = inv.getArgument(1);
            rows.forEach(row -> row.put("salary", null));
            return null;
        }).when(permissions).maskRows(eq("Employee"), anyList());
        ChangeLogServiceImpl service = new ChangeLogServiceImpl();
        ReflectionTestUtils.setField(service, "permissionService", permissions);

        ChangeLog hidden = update("2", values("salary", 300, "name", "C"), values("salary", 400, "name", "D"));

        List<ChangeLog> visible = ReflectionTestUtils.invokeMethod(service, "visibleToReader", List.of(hidden));

        assertThat(visible).hasSize(1);
        assertThat(visible.getFirst().getDataBeforeChange()).doesNotContainKey("salary");
        assertThat(visible.getFirst().getDataAfterChange()).doesNotContainKey("salary").containsEntry("name", "D");
    }
}
