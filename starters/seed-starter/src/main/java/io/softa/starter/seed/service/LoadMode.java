package io.softa.starter.seed.service;

/**
 * How a seed file is written into a scope that may already hold its rows.
 *
 * <p>Platform files always {@link #OVERWRITE}: the file is the data. A tenant file overwrites when the manifest
 * marks it {@code overwrite: true} — rows nobody in the tenant edits, such as the built-in roles — and when the
 * platform admin reapplies it. Every other run of a tenant file, patch files included, is {@link #ADDITIVE}: it
 * runs once per tenant, and what the tenant changed afterwards is the tenant's.
 */
public enum LoadMode {

    /**
     * The file is the data. A row the scope already has is rewritten whole — a field the file leaves out is
     * cleared — and a seeded row the scope deleted is created again. Of the children under a row, the ones
     * that came from a seed and are no longer in the file are deleted; children added by hand carry no
     * binding and are kept.
     */
    OVERWRITE,

    /**
     * Only what the file says. A row the scope already has gets the fields the file names and nothing else;
     * no child is deleted; and a seeded row the scope deleted stays deleted, together with the rows nested in
     * it and any new row that refers to it.
     */
    ADDITIVE
}
