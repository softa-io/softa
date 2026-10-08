package io.softa.framework.orm.service.validation;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The message names each rejected field by its label; the field errors stay keyed by path.
 */
class WriteValidationExceptionTest {

    private static MetaField field(String label, String relatedModel) {
        MetaField field = mock(MetaField.class);
        when(field.getLabel()).thenReturn(label);
        when(field.getRelatedModel()).thenReturn(relatedModel);
        return field;
    }

    @Test
    void aNestedPathReadsAsTheLabelOfTheFieldItEndsOn() {
        MetaField profile = field("Employee Profile", "EmployeeProfile");
        MetaField status = field("Residence Status", null);
        try (MockedStatic<ModelManager> models = Mockito.mockStatic(ModelManager.class)) {
            models.when(() -> ModelManager.getModelFieldOrNull("Employee", "employeeProfileId")).thenReturn(profile);
            models.when(() -> ModelManager.getModelFieldOrNull("EmployeeProfile", "residenceStatus")).thenReturn(status);

            WriteValidationException e = new WriteValidationException("Employee",
                    List.of(new WriteValidationException.FieldError(0, "employeeProfileId.residenceStatus", "Refused.")));

            assertThat(e.getMessage()).isEqualTo("Residence Status: Refused.");
            assertThat(e.fieldErrors()).containsEntry("employeeProfileId.residenceStatus", "Refused.");
        }
    }

    @Test
    void anUnknownFieldKeepsItsPath() {
        try (MockedStatic<ModelManager> models = Mockito.mockStatic(ModelManager.class)) {
            WriteValidationException e = new WriteValidationException("Employee",
                    List.of(new WriteValidationException.FieldError(0, "ghost", "Refused.")));

            assertThat(e.getMessage()).isEqualTo("ghost: Refused.");
        }
    }
}
