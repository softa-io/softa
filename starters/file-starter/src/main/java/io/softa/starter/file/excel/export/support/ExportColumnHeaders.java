package io.softa.starter.file.excel.export.support;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

/**
 * The header an exported column is written under when nobody named one: every column of a dynamic
 * export, and a template column without a {@code customHeader}. One rule for both, so a column reads
 * the same whichever way it was exported.
 */
public final class ExportColumnHeaders {

    /** Between a relation's label and the label of a field of its record, as the screens write it. */
    private static final String RELATION_SEPARATOR = " › ";

    private ExportColumnHeaders() {
    }

    /**
     * The field's label, after the label of each many-to-one its path goes through —
     * {@code departmentId.code} is "Department › Code", {@code employeeId.departmentId.name} is
     * "Employee › Department › Name". Under the last label alone, the columns of two relations onto one
     * model (a manager's and an HR partner's employee code) could not be told apart. A one-to-one hop
     * adds nothing: the row's own sub-record ({@code employeeProfileId.passType}) reads as a field of
     * the row, as the export dialog lists it.
     *
     * @param modelName the model exported
     * @param fieldName a field of it, or a dotted path through its relations
     * @return the header the column is written under
     */
    public static String of(String modelName, String fieldName) {
        if (!fieldName.contains(".")) {
            return ModelManager.getLastFieldOfCascaded(modelName, fieldName).getLabel();
        }
        String[] segments = StringUtils.split(fieldName, ".");
        List<String> labels = new ArrayList<>(segments.length);
        String model = modelName;
        for (int i = 0; i < segments.length - 1; i++) {
            MetaField hop = ModelManager.getModelField(model, segments[i]);
            if (hop.getFieldType() != FieldType.ONE_TO_ONE) {
                labels.add(hop.getLabel());
            }
            model = hop.getRelatedModel();
        }
        labels.add(ModelManager.getModelField(model, segments[segments.length - 1]).getLabel());
        return String.join(RELATION_SEPARATOR, labels);
    }
}
