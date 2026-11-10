package io.softa.starter.user.service;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A model the employee owns and that reaches its department through the employee follows the employee: an
 * exemption on it alone would change nothing, so the wizard has to be able to tell.
 */
class DepartmentGrantsFollowsEmployeeTest {

    private MockedStatic<ModelManager> manager;
    private MetaField ownDepartment;

    private static MetaField field(FieldType type, String related, String relatedField, OnDelete onDelete) {
        MetaField field = mock(MetaField.class);
        when(field.getFieldType()).thenReturn(type);
        when(field.getRelatedModel()).thenReturn(related);
        when(field.getRelatedField()).thenReturn(relatedField);
        when(field.getOnDelete()).thenReturn(onDelete);
        return field;
    }

    @BeforeEach
    void setUp() {
        // Built before any static stubbing starts: Mockito refuses a stub opened inside another one's thenReturn.
        MetaField employeeDepartment = field(FieldType.MANY_TO_ONE, "Department", null, null);
        MetaField toEmployee = field(FieldType.MANY_TO_ONE, "Employee", null, null);
        List<MetaField> employeeFields = List.of(
                field(FieldType.ONE_TO_MANY, "Address", "employeeId", null),
                field(FieldType.ONE_TO_ONE, "Visa", null, OnDelete.CASCADE),
                field(FieldType.MANY_TO_ONE, "Note", null, null));
        ownDepartment = field(FieldType.MANY_TO_ONE, "Department", null, null);

        manager = mockStatic(ModelManager.class);
        manager.when(() -> ModelManager.existModel(anyString())).thenReturn(true);
        manager.when(() -> ModelManager.getModelFieldOrNull(anyString(), anyString())).thenReturn(null);
        // Employee sits in a department; Address, Visa and Note reach it through their employee.
        manager.when(() -> ModelManager.getModelFieldOrNull("Employee", "departmentId")).thenReturn(employeeDepartment);
        for (String model : List.of("Address", "Visa", "Note")) {
            manager.when(() -> ModelManager.getModelFieldOrNull(model, "employeeId")).thenReturn(toEmployee);
        }
        manager.when(() -> ModelManager.getModelFields("Employee")).thenReturn(employeeFields);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void aChildTheEmployeeOwnsFollowsIt() {
        assertThat(DepartmentGrants.followsEmployee("Address")).isTrue();
    }

    @Test
    void aRowDeletedWithTheEmployeeHangsOffItAndDoesNotFollow() {
        assertThat(DepartmentGrants.followsEmployee("Visa")).isFalse();
    }

    @Test
    void aModelTheEmployeeOnlyPointsAtDoesNotFollow() {
        assertThat(DepartmentGrants.followsEmployee("Note")).isFalse();
    }

    @Test
    void aModelPlacedInADepartmentOfItsOwnDoesNotFollow() {
        manager.when(() -> ModelManager.getModelFieldOrNull("Address", "departmentId")).thenReturn(ownDepartment);

        assertThat(DepartmentGrants.followsEmployee("Address")).isFalse();
    }
}
