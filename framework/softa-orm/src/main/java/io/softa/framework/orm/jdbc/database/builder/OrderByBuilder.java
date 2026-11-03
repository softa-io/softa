package io.softa.framework.orm.jdbc.database.builder;

import java.util.List;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.jdbc.database.SqlWrapper;
import io.softa.framework.orm.meta.ModelManager;

/**
 * OrderBy Builder
 * For paged queries:
 *      When `orders` in flexQuery is empty, using the `defaultOrder` configuration of model.
 *      When 'defaultOrder' is not configured, using the global default order `DEFAULT_PAGED_ORDER`.
 *      A cursor page's order also ends with the physical primary key (`sliceId` on a timeline model).
 * For non-paged queries:
 *      Order according to the `orders` in flexQuery, or do not specify the sort when it is empty.
 * For DISTINCT queries:
 *      Only explicit `orders` are applied, never an implicit one — MySQL (error 3065) and
 *      PostgreSQL reject ORDER BY columns outside the DISTINCT select list.
 * For aggregate queries:
 *      Orders are kept only for fields the query grouped on; the rest are dropped, explicit or not.
 */
public class OrderByBuilder extends BaseBuilder implements SqlClauseBuilder {

    private Page<?> page;

    public OrderByBuilder(SqlWrapper sqlWrapper, FlexQuery flexQuery) {
        super(sqlWrapper, flexQuery);
    }

    public <T> OrderByBuilder(SqlWrapper sqlWrapper, FlexQuery flexQuery, Page<T> page) {
        super(sqlWrapper, flexQuery);
        this.page = page;
    }

    public void build() {
        // Must be after groupBy processing
        handleOrderBy();
    }

    /**
     * Update sqlWrapper according to whether it is paged and the `orders` attribute of flexQuery
     */
    public void handleOrderBy() {
        Orders orders = flexQuery.getOrders();
        // A DISTINCT projection deduplicates rows, so a column outside its select list has no
        // defined value to sort by — MySQL (error 3065) and PostgreSQL both reject it. Never
        // inject an implicit order into a DISTINCT query; explicit `orders` pass through as-is.
        boolean distinct = flexQuery.isDistinct();
        if (orders == null && !flexQuery.isAggregate() && !distinct) {
            // When `orders` in flexQuery is empty, using the `defaultOrder` configuration of model.
            Orders defaultOrder = ModelManager.getModel(mainModelName).getDefaultOrder();
            if (defaultOrder != null && !defaultOrder.isEmpty()) {
                orders = defaultOrder;
            } else if (page != null && !flexQuery.isAggregate()) {
                // In page query, if the order is not specified, and it is not an aggregate query, use the default order.
                orders = Orders.of(ModelConstant.DEFAULT_PAGED_ORDER);
            }
        }
        if (orders != null) {
            for (List<String> order : orders.getOrderList()) {
                if (!isSortable(order.get(0))) {
                    continue;
                }
                // Support cascade fields (e.g. deptId.managerId.name) and dynamic cascaded
                // field aliases declared on the model (auto-expanded by parseLogicField).
                String aliasField = this.parseLogicField(order.get(0), false);
                sqlWrapper.orderBy(aliasField, order.get(1));
            }
            // A cursor page is read page after page, so its order has to be total, or a row could land on
            // two pages or on none: the physical primary key ends the order unless the order names it. On a
            // timeline model that is `sliceId`, since the slices of one record share its `id`. The key is
            // the framework's addition, not the caller's choice, so it is not recorded as a field accessed.
            // Skipped for DISTINCT: the key is outside the select list (same 3065 failure).
            // Skipped when grouping does not carry the key, for the reason given on isSortable.
            if (page != null && page.isCursorPage() && !distinct) {
                String primaryKey = ModelManager.getModelPrimaryKey(mainModelName);
                if (!orders.getFields().contains(primaryKey) && isSortable(primaryKey)) {
                    String column = ModelManager.getModelFieldColumn(mainModelName, primaryKey);
                    sqlWrapper.orderBy(SqlWrapper.MAIN_TABLE_ALIAS + "." + column, Orders.ASC);
                }
            }
        }
    }

    /**
     * Whether `field` may take part in ORDER BY.
     *
     * <p>Unrestricted for an ordinary query. An aggregate query collapses rows, so only the fields it
     * grouped on still hold one value per returned row — sorting by any other column is something the
     * database refuses outright, not a preference it quietly ignores.
     *
     * <p>Dropping the field is deliberate. The order a client sends with a grouped list is almost
     * always the default it attaches to every list (a creation timestamp, say), not a choice about
     * this query; the grouping is the choice. Refusing the whole query over the part the caller did
     * not ask for turns grouping into an error with no way back, while dropping it returns the
     * grouped rows the caller wanted, in the order the grouping itself implies.
     *
     * @param field logic field name taken from the orders parameter
     * @return true when the field may appear in ORDER BY
     */
    private boolean isSortable(String field) {
        if (!flexQuery.isAggregate()) {
            return true;
        }
        return sqlWrapper.getGroupByLogicFields().contains(field);
    }

}
