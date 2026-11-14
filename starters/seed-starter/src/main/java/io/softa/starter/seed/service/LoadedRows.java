package io.softa.starter.seed.service;

import java.util.List;
import java.util.Map;

/**
 * The rows a tenant load wrote, by model, ids as strings.
 *
 * @param created the rows the load created — a bound row found deleted and created again among them
 * @param updated the rows an earlier load had created that this one wrote to. An update that changed nothing
 *                does not count; a timeline entity does whenever one of its versions is written, as a version
 *                write does not say whether it changed anything
 */
public record LoadedRows(Map<String, List<String>> created, Map<String, List<String>> updated) {

    /** Whether the load wrote nothing: every row was already as the file has it, or was left out. */
    public boolean isEmpty() {
        return created.isEmpty() && updated.isEmpty();
    }
}
