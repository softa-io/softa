package io.softa.starter.user.dto;

import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * A list view arrangement as the frontend reads and writes it.
 *
 * <p>On save each part is optional: a null part is left as stored, so the Columns dialog, the export
 * dialog and the side tree each save their own part without carrying the others'.
 */
@Data
public class UserListViewPreferenceDTO {

    private String viewStateKey;

    private String modelName;

    private List<String> columnOrder;

    private List<String> hiddenColumns;

    /**
     * Columns the person added that the list does not declare — field paths, at most one level into a
     * one-to-one record. Which ones only; {@link #columnOrder} carries their place.
     */
    private List<String> addedColumns;

    private Integer freezeColumnIndex;

    private List<String> exportFields;

    /** Keyed by the collection field on the list's model; values are fields of its related model. */
    private Map<String, List<String>> exportObjectFields;

    /**
     * The list's other view state, such as {@code sidePanelCollapsed}. On save it is merged key by
     * key into what is stored — a key sent as null is removed — so one setting can be saved without
     * carrying the others.
     */
    private Map<String, Object> viewSettings;
}
