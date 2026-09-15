package io.softa.framework.orm.service.validation;

import java.util.ArrayList;
import java.util.List;

/** The rejections accumulated over one write, shared by every {@link WriteContext} of that write. */
final class WriteValidationErrors {

    private final List<WriteValidationException.FieldError> errors = new ArrayList<>();

    void add(int rowIndex, String field, String message) {
        errors.add(new WriteValidationException.FieldError(rowIndex, field, message));
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }

    List<WriteValidationException.FieldError> errors() {
        return List.copyOf(errors);
    }
}
