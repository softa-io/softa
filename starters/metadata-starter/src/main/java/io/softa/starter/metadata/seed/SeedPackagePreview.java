package io.softa.starter.metadata.seed;

import java.util.List;

/**
 * A tenant seed package a tenant would be given, and its files for the tenant's country.
 *
 * @param key    package key
 * @param name   package name
 * @param module plan module the package needs, or null for every plan
 * @param files  its files for the country, in load order
 */
public record SeedPackagePreview(String key, String name, String module, List<String> files) {
}
