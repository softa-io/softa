package io.softa.starter.studio.release.preview;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Top-level response of {@code previewVersion} / {@code previewWorkItemChanges}.
 * <p>
 * The three roots are part of the version-controlled metadata contract (see
 * {@code MetadataConstant.VERSION_CONTROL_MODELS}); making them explicit named
 * fields means clients can render their three tabs (and badge counts) without
 * filtering or grouping the response, and an empty group is still a present
 * empty list rather than an absent array entry.
 */
@Data
@NoArgsConstructor
public class PreviewTreeDTO {

    /** Root nodes whose {@code modelName} is {@code DesignModel}. */
    private List<PreviewNodeDTO> models = new ArrayList<>();

    /** Root nodes whose {@code modelName} is {@code DesignOptionSet}. */
    private List<PreviewNodeDTO> optionSets = new ArrayList<>();

    /** Root nodes whose {@code modelName} is {@code DesignNavigation}. */
    private List<PreviewNodeDTO> navigations = new ArrayList<>();

}
