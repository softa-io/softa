package io.softa.starter.message.template;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A small set of models for the template tests: an order with a customer, a status, an amount and
 * its lines, each line with a product.
 */
final class TemplateTestModels implements TemplateModels {

    private final Map<String, Map<String, MetaField>> models = new HashMap<>();
    Map<String, Set<String>> withheld = Map.of();

    TemplateTestModels() {
        field("Order", "id", FieldType.LONG, null);
        field("Order", "code", FieldType.STRING, null);
        field("Order", "status", FieldType.OPTION, null);
        field("Order", "amount", FieldType.BIG_DECIMAL, null);
        field("Order", "urgent", FieldType.BOOLEAN, null);
        field("Order", "dueDate", FieldType.DATE, null);
        field("Order", "customerId", FieldType.MANY_TO_ONE, "Customer");
        field("Order", "lines", FieldType.ONE_TO_MANY, "OrderLine");
        // A copy of the customer's manager, declared on the order.
        field("Order", "managerId", FieldType.MANY_TO_ONE, "Employee", "customerId.managerId");
        field("Customer", "id", FieldType.LONG, null);
        field("Customer", "name", FieldType.STRING, null);
        field("Customer", "taxNumber", FieldType.STRING, null);
        field("Customer", "managerId", FieldType.MANY_TO_ONE, "Employee");
        field("Customer", "orders", FieldType.ONE_TO_MANY, "Order");
        field("Employee", "id", FieldType.LONG, null);
        field("Employee", "name", FieldType.STRING, null);
        field("OrderLine", "id", FieldType.LONG, null);
        field("OrderLine", "name", FieldType.STRING, null);
        field("OrderLine", "qty", FieldType.INTEGER, null);
        field("OrderLine", "productId", FieldType.MANY_TO_ONE, "Product");
        field("OrderLine", "tags", FieldType.MANY_TO_MANY, "Tag");
        field("Product", "id", FieldType.LONG, null);
        field("Product", "code", FieldType.STRING, null);
    }

    /** MetaField has no public setters: a stub answering the few getters the template code reads. */
    private MetaField field(String model, String name, FieldType type, String related) {
        return field(model, name, type, related, null);
    }

    private MetaField field(String model, String name, FieldType type, String related, String copiedFrom) {
        MetaField field = mock(MetaField.class);
        when(field.getModelName()).thenReturn(model);
        when(field.getFieldName()).thenReturn(name);
        when(field.getFieldType()).thenReturn(type);
        when(field.getRelatedModel()).thenReturn(related);
        when(field.getCascadedField()).thenReturn(copiedFrom);
        models.computeIfAbsent(model, key -> new HashMap<>()).put(name, field);
        return field;
    }

    @Override
    public boolean exists(String model) {
        return models.containsKey(model);
    }

    @Override
    public MetaField field(String model, String fieldName) {
        return model == null ? null : models.getOrDefault(model, Map.of()).get(fieldName);
    }

    /** A path is readable when each part is a field and all but the last are to-one relations. */
    @Override
    public String pathProblem(String model, String path) {
        String current = model;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            MetaField field = field(current, parts[i]);
            if (field == null) {
                return "'" + parts[i] + "' is not a field of " + current + ".";
            }
            if (i < parts.length - 1 && !FieldType.TO_ONE_TYPES.contains(field.getFieldType())) {
                return "'" + parts[i] + "' cannot be followed.";
            }
            current = field.getRelatedModel();
        }
        return null;
    }

    @Override
    public Map<String, Set<String>> withheld() {
        return withheld;
    }
}
