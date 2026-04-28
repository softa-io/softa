package io.softa.starter.studio.release.preview;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.enums.ChangeKind;

/**
 * Recursive node returned by the preview API.
 * <p>
 * Roots are {@code DesignModel}, {@code DesignOptionSet}, and {@code DesignNavigation}.
 * Children include the corresponding sub-records (fields, indexes, items, views) and translation
 * rows. {@link ChangeKind#INDIRECT} nodes are emitted when a parent's row is not itself directly
 * written but one of its descendants changed — the parent has to appear so the changed subtree
 * has somewhere to hang under, even though only the aggregate (not this specific row) changed.
 */
@Data
@NoArgsConstructor
public class PreviewNodeDTO {

    private String modelName;
    private Long rowId;
    private ChangeKind kind;
    private RowChangeDTO record;
    private List<PreviewNodeDTO> children = new ArrayList<>();

    public PreviewNodeDTO(String modelName, Long rowId, ChangeKind kind, RowChangeDTO record) {
        this.modelName = modelName;
        this.rowId = rowId;
        this.kind = kind;
        this.record = record;
    }

}
