package io.softa.starter.seed.manifest;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * What a seed file does after a scope has loaded it, as the manifest's {@code mode} says: whether a change to the
 * file reaches that scope again, and how. A platform file is {@link #OVERWRITE} or {@link #STARTUP}; a tenant file
 * is {@link #ONCE}, {@link #APPEND} or {@link #OVERWRITE}.
 */
@Schema(name = "SeedFileMode", description = "What a seed file does after a scope has loaded it, as the manifest's "
        + "mode says. overwrite: loads again, whole, whenever it changes (a platform file's default). startup: a "
        + "platform file the build owns, loaded whole at startup wherever it is pending, never from the Seed Data "
        + "page. once: runs once in each tenant (a tenant file's default). append: a tenant file that runs again "
        + "whenever it changes, creating only the rows a tenant lacks.")
@Getter
@AllArgsConstructor
public enum SeedFileMode {

    /**
     * The file is the data: it loads again, whole, whenever it changes — a field it no longer names is cleared, a
     * seeded child it no longer lists is deleted. A platform file's default; on a tenant file, only for rows nobody
     * in a tenant edits, such as the built-in roles.
     */
    OVERWRITE("overwrite"),

    /**
     * A platform file the build owns: it loads whole at startup wherever it is pending, before the application takes
     * traffic and before the in-memory snapshots are built from its rows, and never from the Seed Data page.
     */
    STARTUP("startup"),

    /**
     * Runs once in each tenant, and what the tenant changes afterwards is the tenant's: an edit to the file reaches
     * only the tenants set up after it. A tenant file's default.
     */
    ONCE("once"),

    /**
     * A tenant file that runs again whenever it changes and creates only the rows the tenant lacks — a new row at any
     * depth of the file. A row the tenant has is not written to, whatever the file says of it now, and a seeded row
     * the tenant deleted stays deleted.
     */
    APPEND("append");

    /** The modes a platform file takes, its default first. */
    public static final List<SeedFileMode> PLATFORM_MODES = List.of(OVERWRITE, STARTUP);

    /** The modes a tenant file takes, its default first. */
    public static final List<SeedFileMode> TENANT_MODES = List.of(ONCE, APPEND, OVERWRITE);

    /** As the manifest writes it. */
    @JsonValue
    private final String code;

    /** Whether a change to the file reaches a scope that already loaded it: in every mode but {@link #ONCE}. */
    public boolean reloadsWhenChanged() {
        return this != ONCE;
    }

    /** The mode the manifest writes as {@code code}, if there is one. */
    public static Optional<SeedFileMode> of(String code) {
        return Arrays.stream(values()).filter(mode -> mode.code.equals(code)).findFirst();
    }
}
