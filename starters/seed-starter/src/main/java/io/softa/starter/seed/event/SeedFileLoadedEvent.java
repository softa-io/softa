package io.softa.starter.seed.event;

import java.util.List;
import java.util.Map;

/**
 * A tenant seed file finished loading into a tenant. Published inside the file's own transaction,
 * in the tenant's context with permission checks skipped — the same context the load ran in. A
 * listener that throws rolls the file back and records it as failed; once the cause is fixed, the
 * tenant runs the file again.
 *
 * <p>Only files that wrote rows announce themselves: a run that found every row as the file has it
 * has nothing to act on.
 *
 * @param tenantId    the tenant the file loaded into
 * @param fileName    the file, as the seed manifest names it
 * @param createdRows the rows this run created, by model; ids as strings. Rows an earlier load had
 *                    already created are not included
 * @param updatedRows the rows an earlier load had created that this run wrote to, by model; ids as
 *                    strings. A run of a file a tenant already has — a reapply, an overwrite file —
 *                    is what produces them
 */
public record SeedFileLoadedEvent(Long tenantId, String fileName, Map<String, List<String>> createdRows,
                                  Map<String, List<String>> updatedRows) {
}
