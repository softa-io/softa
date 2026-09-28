package io.softa.starter.metadata.seed;

import java.util.List;

/**
 * One seed file as the manifest registers it.
 *
 * @param file        file name, relative to the level's directory (e.g. {@code LeaveType.SG.json})
 * @param level       who the rows belong to, and so which directory the file is read from
 * @param countries   the countries whose tenants need the file; empty = every country
 * @param dependsOn   files of the same level that must be loaded before this one, because its rows
 *                    refer to theirs
 * @param push        the one change that must still reach tenants that already loaded the file
 * @param retired     preIds this file used to carry and must never carry again: a tenant that loaded
 *                    the old row still binds the preId to it, so a new row under the same preId would
 *                    never reach that tenant
 * @param packageKey  key of the package the file is registered in
 */
public record SeedFile(String file, SeedLevel level, List<String> countries, List<String> dependsOn,
                       SeedPushScope push, List<String> retired, String packageKey) {

    public SeedFile {
        countries = List.copyOf(countries);
        dependsOn = List.copyOf(dependsOn);
        retired = List.copyOf(retired);
    }

    /** Classpath location of the file, e.g. {@code data-tenant/LeaveType.SG.json}. */
    public String path() {
        return level.getDataDir() + file;
    }
}
