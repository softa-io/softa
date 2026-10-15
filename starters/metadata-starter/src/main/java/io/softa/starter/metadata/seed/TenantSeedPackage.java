package io.softa.starter.metadata.seed;

import java.util.List;

/**
 * A tenant seed package a tenant is reached with: its files the tenant is due or holds rows from.
 *
 * @param key      package key
 * @param name     package name
 * @param module   plan module the package needs, or null for every plan
 * @param files    its files reaching the tenant, in load order
 * @param entitled whether the tenant's plan and countries call for it; false for a package it only still
 *                 holds, after a downgrade — it keeps receiving that package's new rows, hidden by the plan
 */
public record TenantSeedPackage(String key, String name, String module, List<String> files, boolean entitled) {
}
