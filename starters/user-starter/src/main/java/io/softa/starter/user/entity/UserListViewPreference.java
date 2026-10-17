package io.softa.starter.user.entity;

import java.io.Serial;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.OnDelete;

/**
 * How one person last arranged one list view — a table, card or board: its columns, the fields of
 * its Dynamic Export, and its other view state such as whether its side tree is collapsed.
 *
 * <p>One row per account and list. Held on the server rather than in the browser because it belongs
 * to the person, not the machine. The account is per tenant, so the row is too.
 *
 * <p>Read and written only through {@code UserListViewPreferenceController}, which binds every call to
 * the caller. The model exposes no generic endpoints, and has no employee or department anchor a role
 * could scope it by.
 *
 * <p>The field lists hold field names and are never authority: they are filtered against what the
 * reader may read every time they are served or saved, so a field whose sensitive set is withdrawn
 * drops out of the arrangement rather than coming back as a column or an export column.
 *
 * <p>A row, for illustration:
 * <pre>
 * viewStateKey         table|Order|/sales/order|          (view kind | model | route | tab)
 * modelName            Order
 * columnOrder          ["orderNo","customer","orderDate","amount","status","customerId.creditLimit"]
 * hiddenColumns        ["remark"]
 * addedColumns         ["customerId.creditLimit"]
 * freezeColumnIndex    1
 * exportFields         ["orderNo","customer","amount"]
 * exportObjectFields   {"lines":["product","quantity","price"]}   (keyed by the collection field)
 * viewSettings         {"sidePanelCollapsed":true}
 * </pre>
 * A part left null is not arranged, and the list uses its own default for it; a row whose parts are
 * all null is deleted.
 *
 * <p>The view state key is opaque here — compared, never parsed. The client builds it as
 * {@code <view kind>|<model>|<route>|<tab>}: the kind of view ({@code table}, {@code card},
 * {@code board}), the model it lists, the page's path exactly as the browser has it, and the active
 * tab of a tabbed page (empty when the page has none, so the key then ends in {@code |}). Each
 * distinct key is a list of its own, with an arrangement of its own:
 * <pre>
 * table|Order|/sales/order|             the order list, a page without tabs
 * table|Order|/sales/order|pending      the same page's "pending" tab
 * table|Order|/sales/order|shipped      its "shipped" tab — another row
 * card|Order|/sales/order|              the same orders shown as cards — another row
 * board|Task|/projects/task|            a kanban board of tasks
 * table|OrderLine|/sales/order/42|      a list inside the page of order 42; the path carries the id,
 *                                       so the page of order 43 keeps an arrangement of its own
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "User List View Preference",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        multiTenant = true,
        description = "A person's own arrangement of a list view: columns, Dynamic Export fields, view state.")
@Index(indexName = "uk_user_list_view_preference_user_view", fields = {"tenantId", "userId", "viewStateKey"},
        unique = true, message = "This list already has a saved arrangement.")
public class UserListViewPreference extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    /** The account the arrangement belongs to; always the caller's own. Goes with the account. */
    @Field(label = "User", fieldType = FieldType.MANY_TO_ONE, relatedModel = UserAccount.class,
            required = true, onDelete = OnDelete.CASCADE)
    private Long userId;

    /** The list view, as the frontend keys its view state: view kind, model, route and tab. */
    @Field(label = "View State Key", required = true, length = 255)
    private String viewStateKey;

    /** The model the list shows — what the field names are checked against. */
    @Field(label = "Model", required = true, length = 64)
    private String modelName;

    /** Column ids in display order, as a JSON array. Empty means the list's own order. */
    @Field(label = "Column Order", fieldType = FieldType.TEXT)
    private String columnOrder;

    /** Column ids the person hid, as a JSON array. */
    @Field(label = "Hidden Columns", fieldType = FieldType.TEXT)
    private String hiddenColumns;

    /**
     * Columns the person added that the list does not declare, as a JSON array of field paths: a field
     * of the list's model ({@code ipaPosition}) or one level into a one-to-one record
     * ({@code employeeProfileId.passNumber}). Says only which ones; {@link #columnOrder} says where.
     *
     * <p>Kept apart from the order because the order alone cannot tell an added column from a stale
     * one: a reader that keeps only the columns the list declares would otherwise drop every column
     * the person added, the next time the list opens.
     */
    @Field(label = "Added Columns", fieldType = FieldType.TEXT)
    private String addedColumns;

    /** How many leading columns are frozen. */
    @Field(label = "Freeze Column Index")
    private Integer freezeColumnIndex;

    /** Dynamic Export fields in export order, as a JSON array. */
    @Field(label = "Export Fields", fieldType = FieldType.TEXT)
    private String exportFields;

    /** Dynamic Export fields of each object hanging off the record, as a JSON object. */
    @Field(label = "Export Object Fields", fieldType = FieldType.TEXT)
    private String exportObjectFields;

    /**
     * The list's other view state, as a JSON object — whether its side tree is collapsed, and
     * whatever the list remembers next. Holds no field names: those live in the columns above, where
     * the server can check them against what the reader may read.
     */
    @Field(label = "View Settings", fieldType = FieldType.TEXT)
    private String viewSettings;
}
