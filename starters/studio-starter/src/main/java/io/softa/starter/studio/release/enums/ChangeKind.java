package io.softa.starter.studio.release.enums;

import io.softa.starter.studio.release.preview.PreviewNodeDTO;

/**
 * Kind of change a {@link PreviewNodeDTO} represents.
 */
public enum ChangeKind {

    CREATE,
    UPDATE,
    DELETE,
    /**
     * The row itself was not directly written, but one of its descendants in the
     * aggregate hierarchy changed — so the node is included to root the changed
     * subtree. Domain-wise the aggregate has changed; only this row's persistence
     * record is untouched.
     */
    INDIRECT

}
