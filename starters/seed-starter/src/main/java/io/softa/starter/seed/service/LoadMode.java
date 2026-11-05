package io.softa.starter.seed.service;

/**
 * How a seed file is written into a scope that may already hold its rows.
 *
 * <p>In every mode, a row the scope has that is written to gets the fields the file names and nothing else: a
 * field the file leaves out keeps what the row holds — the default it was created with, or what the scope wrote
 * since — and a file clears a field by naming it with null. The modes differ over whole rows.
 *
 * <p>Platform files always {@link #OVERWRITE}. A tenant file overwrites in the manifest's mode {@code overwrite} —
 * rows nobody in the tenant edits, such as the built-in roles — and when the platform admin reapplies it, and it
 * {@link #APPEND}s in mode {@code append}. Every other run of a tenant file, patch files included, is
 * {@link #ADDITIVE}: it runs once per tenant, and what the tenant changed afterwards is the tenant's.
 */
public enum LoadMode {

    /**
     * The file's rows win. A row the scope already has gets the fields the file names, whatever the scope wrote
     * to them, and a seeded row the scope deleted is created again. Of the children under a row, the ones that
     * came from a seed and are no longer in the file are deleted; children added by hand carry no binding and
     * are kept.
     */
    OVERWRITE,

    /**
     * Only what the file says. A row the scope already has gets the fields the file names, as in every mode; no
     * child is deleted; and a seeded row the scope deleted stays deleted, together with the rows nested in it and
     * any new row that refers to it.
     */
    ADDITIVE,

    /**
     * Only the rows the scope lacks. A row with no binding in the scope is created, wherever it sits in the file —
     * a new child under a row the scope has included; a row the scope has gets nothing written to it, whatever the
     * file says of it now; and, as in {@link #ADDITIVE}, no child is deleted and a seeded row the scope deleted
     * stays deleted, together with the rows nested in it and any new row that refers to it.
     */
    APPEND
}
