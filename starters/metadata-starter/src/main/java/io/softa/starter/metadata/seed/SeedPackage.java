package io.softa.starter.metadata.seed;

import java.util.List;

/**
 * A group of seed files handed out together.
 *
 * @param key       unique key of the package
 * @param name      human-readable name
 * @param module    the plan module a tenant must be entitled to for the package to apply; empty = every
 *                  tenant, whatever its plan
 * @param changelog what the latest change to the package was, for whoever applies it
 * @param files     the files, in the order they are written in the manifest
 */
public record SeedPackage(String key, String name, String module, String changelog, List<SeedFile> files) {

    public SeedPackage {
        files = List.copyOf(files);
    }
}
