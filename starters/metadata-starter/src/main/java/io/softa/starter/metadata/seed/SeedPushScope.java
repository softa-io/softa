package io.softa.starter.metadata.seed;

import java.util.Arrays;
import java.util.Optional;

import lombok.Getter;

/**
 * Which change to a {@link SeedLevel#TENANT} file must still reach tenants that already loaded it.
 *
 * <p>By default a tenant-level file only gives an existing tenant what it does not have yet — a new file,
 * a new top-level row — and never changes or removes a row the tenant already holds, because the tenant
 * may have edited it. A few changes break a feature when they are held back that way; a file declares the
 * one it needs here, and nothing else about the default changes.
 */
@Getter
public enum SeedPushScope {

    /** No exception: new files and new rows only. */
    NONE("None"),

    /**
     * Import-template columns whose model field no longer exists are removed from every tenant's copy of
     * the template. Left in place, such a column makes the template fail to download.
     */
    INVALID_COLUMNS("InvalidColumns"),

    /**
     * Items added under a row a tenant already has — new options of an existing option set — are added to
     * that tenant's row, skipping any the tenant already holds. Code that refers to an item by its code
     * cannot find it otherwise.
     */
    NEW_NESTED_ITEMS("NewNestedItems");

    /** The value written in the manifest. */
    private final String code;

    SeedPushScope(String code) {
        this.code = code;
    }

    /** The scope a manifest value names, or empty when it names none. */
    public static Optional<SeedPushScope> ofCode(String code) {
        return Arrays.stream(values()).filter(scope -> scope.code.equals(code)).findFirst();
    }
}
