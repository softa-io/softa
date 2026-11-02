package io.softa.starter.flow.service.support;

import java.util.List;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.FilterType;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;

/**
 * The conditions a caller adds to a list the flow endpoints serve — the approval center's task boxes, the instance
 * searches: {@code filters} and {@code orders} in the grammar {@link Filters} and {@link Orders} parse, as a
 * metadata-driven table sends them. They narrow and sort what the list returns anyway: ANDed onto its own
 * constraints — the caller's pin, the box, the monitor's data scope, the typed filters — never in their place, so a
 * filter naming another actor finds nothing rather than their rows.
 *
 * <p>They may name only the fields the model stores. A dynamic field has no column to filter on; a dotted path
 * reaches into another model, which a self-service list — read past the caller's row scope — would answer from rows
 * the caller cannot see; and anything else is refused by its name ({@code 400}) rather than failing as SQL. The one
 * name allowed besides is {@code searchName} in a filter, the toolbar search: it stands for the model's
 * {@code @Model(searchName)} fields, stored ones.
 */
public final class CallerConditions {

    private CallerConditions() {
    }

    /**
     * Refuses a filter or an order on {@code modelName} that names anything but a field it stores — a field a filter
     * compares against ({@code {{ @field }}}) included.
     */
    public static void requireStoredFields(String modelName, Filters filters, Orders orders) {
        if (filters != null) {
            requireStoredFields(modelName, filters);
        }
        if (orders != null) {
            orders.getOrderList().forEach(unit -> requireStored(modelName, unit.getFirst(), "sort"));
        }
    }

    /** The list's own conditions with the caller's ANDed on, each its own subtree: an OR in theirs stays in it. */
    public static Filters and(Filters own, Filters callers) {
        return Filters.isEmpty(callers) ? own : Filters.and(own, callers);
    }

    /**
     * The caller's orders, then {@code id} in the direction of their first unless they sort by it — rows tied on
     * what they sort by keep one order from page to page; without orders of theirs, the list's default.
     */
    public static Orders ordersOr(Orders callers, Orders defaults) {
        if (callers == null || callers.isEmpty()) {
            return defaults;
        }
        List<List<String>> units = callers.getOrderList();
        Orders orders = new Orders();
        units.forEach(unit -> add(orders, unit.getFirst(), unit.get(1)));
        if (!callers.getFields().contains(ModelConstant.ID)) {
            add(orders, ModelConstant.ID, units.getFirst().get(1));
        }
        return orders;
    }

    private static void requireStoredFields(String modelName, Filters filters) {
        if (FilterType.TREE.equals(filters.getType())) {
            filters.getChildren().forEach(child -> requireStoredFields(modelName, child));
        } else if (FilterType.LEAF.equals(filters.getType())) {
            FilterUnit unit = filters.getFilterUnit();
            unit.getEffectiveFields().stream()
                    .filter(field -> !ModelConstant.SEARCH_NAME.equals(field))
                    .forEach(field -> requireStored(modelName, field, "filter"));
            if (Operator.COMPARISON_OPERATORS.contains(unit.getOperator())
                    && unit.getValue() instanceof String value && PlaceholderUtils.isReservedField(value)) {
                requireStored(modelName, PlaceholderUtils.parsePlaceholder(value).getContent(), "filter");
            }
        }
    }

    private static void requireStored(String modelName, String field, String use) {
        if (!ModelManager.existField(modelName, field) || !ModelManager.isStored(modelName, field)) {
            throw new FlowActionValidationException(
                    "Cannot " + use + " by '" + field + "': it is not a stored field of " + modelName);
        }
    }

    private static void add(Orders orders, String field, String direction) {
        if (Orders.DESC.equals(direction)) {
            orders.addDesc(field);
        } else {
            orders.addAsc(field);
        }
    }
}
