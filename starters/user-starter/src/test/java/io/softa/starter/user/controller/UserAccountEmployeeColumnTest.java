package io.softa.starter.user.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The account list's Employee column: the name and code of the employee record each account signs in. */
@SuppressWarnings("unchecked")
class UserAccountEmployeeColumnTest {

    private final ModelService<Long> modelService = mock(ModelService.class);
    private final UserAccountController controller = new UserAccountController();
    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void anHrDeployment() {
        ReflectionTestUtils.setField(controller, "modelService", modelService);
        modelManager = Mockito.mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel("Employee")).thenReturn(true);
    }

    @AfterEach
    void closeStatic() {
        modelManager.close();
    }

    private static Map<String, Object> row(Object id) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        return row;
    }

    @Test
    void eachRow_carriesItsEmployee_andARowWithoutOneCarriesNulls() {
        when(modelService.searchList(eq("Employee"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("id", 9L, "userId", 1L, "fullName", "Ada Lovelace", "code", "E001")));
        Map<String, Object> linked = row("1");
        Map<String, Object> unlinked = row(2L);

        ReflectionTestUtils.invokeMethod(controller, "stampEmployee", new ArrayList<>(List.of(linked, unlinked)));

        assertThat(linked).containsEntry("employeeName", "Ada Lovelace").containsEntry("employeeCode", "E001");
        assertThat(unlinked).containsEntry("employeeName", null).containsEntry("employeeCode", null);
    }

    @Test
    void withoutAnEmployeeModel_everyRowCarriesNulls() {
        modelManager.when(() -> ModelManager.existModel("Employee")).thenReturn(false);
        Map<String, Object> row = row(1L);

        ReflectionTestUtils.invokeMethod(controller, "stampEmployee", new ArrayList<>(List.of(row)));

        assertThat(row).containsEntry("employeeName", null).containsEntry("employeeCode", null);
        verify(modelService, never()).searchList(eq("Employee"), any(FlexQuery.class));
    }

    @Test
    void aWriteOfTheDerivedColumns_isDropped() {
        Map<String, Object> write = new HashMap<>(Map.of("id", 1L, "nickname", "Ada", "employeeName", "x", "employeeCode", "y"));

        ReflectionTestUtils.invokeMethod(UserAccountController.class, "dropDerivedLock", write);

        assertThat(write).containsOnlyKeys("id", "nickname");
    }
}
