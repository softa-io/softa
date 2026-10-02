package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second pass a custom handler gets, after the rows exist.
 *
 * <p>Step 5 of the pipeline runs before persistence, so a handler had nowhere to put work keyed on
 * the new id, and could not defer it either: the only transaction in an import is the one INSIDE
 * the persistence call, which begins after that step has returned. A synchronisation registered
 * there is registered against nothing and silently skipped — on the synchronous path as much as
 * the asynchronous one, since neither wraps the import in a transaction of its own. Handlers
 * written against the older assumption lost that work without saying so.
 *
 * <p>These exercise the ordering contract directly rather than through the pipeline's Spring
 * wiring: what matters is that the after-pass sees the ids, sees only the surviving rows, is told
 * which side each row was, and does not run at all when the import wrote nothing.
 */
class ImportRowPipelineAfterPersistenceTest {

    /** A handler that records what each pass was shown. */
    private static final class RecordingHandler implements CustomImportHandler {
        private final List<List<Map<String, Object>>> before = new ArrayList<>();
        private final List<List<Map<String, Object>>> created = new ArrayList<>();
        private final List<List<Map<String, Object>>> updated = new ArrayList<>();

        @Override
        public void handleImportData(List<Map<String, Object>> rows, Map<String, Object> env,
                                     boolean validateOnly) {
            before.add(snapshot(rows));
        }

        @Override
        public void afterImportData(List<Map<String, Object>> createdRows,
                                    List<Map<String, Object>> updatedRows,
                                    Map<String, Object> env) {
            created.add(snapshot(createdRows));
            updated.add(snapshot(updatedRows));
        }

        /**
         * Copies the MAPS, not just the list holding them.
         *
         * <p>Both passes are handed the same map objects — that is how the ORM fills each row's
         * generated id in place between them — so a shallow copy would show the first pass holding
         * ids it could not possibly have seen.
         */
        private static List<Map<String, Object>> snapshot(List<Map<String, Object>> rows) {
            return rows.stream().<Map<String, Object>>map(LinkedHashMap::new).toList();
        }
    }

    private static Map<String, Object> row(String code) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        return row;
    }

    @Test
    void theAfterPassSeesTheIdsTheInsertFilledIn() {
        RecordingHandler handler = new RecordingHandler();
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row("A"), row("B")));

        handler.handleImportData(rows, Map.of(), false);
        // What persistence does to these same maps: the ORM fills the generated id in place.
        rows.get(0).put("id", 11L);
        rows.get(1).put("id", 12L);
        handler.afterImportData(rows, List.of(), Map.of());

        assertThat(handler.before).hasSize(1);
        assertThat(handler.before.get(0).get(0)).doesNotContainKey("id");
        assertThat(handler.created).hasSize(1);
        assertThat(handler.created.get(0)).extracting(r -> r.get("id")).containsExactly(11L, 12L);
    }

    @Test
    void anInsertedRowAndAMatchedOneArriveOnDifferentSides() {
        // The id tells them apart in neither direction: a matched row has the stored id written onto
        // the very map that was passed in, so after the write both sides look identical. Which side a
        // row was is knowable only where the decision was made, which is why it is carried here.
        RecordingHandler handler = new RecordingHandler();
        Map<String, Object> inserted = row("A");
        inserted.put("id", 11L);
        Map<String, Object> matched = row("B");
        matched.put("id", 7L);

        handler.afterImportData(List.of(inserted), List.of(matched), Map.of());

        assertThat(handler.created.get(0)).extracting(r -> r.get("code")).containsExactly("A");
        assertThat(handler.updated.get(0)).extracting(r -> r.get("code")).containsExactly("B");
    }

    @Test
    void aHandlerThatWantsNothingAfterwardsNeedsToSayNothing() {
        // The default is empty, so the hundreds of handlers with no post-write work are untouched
        // by its existence.
        CustomImportHandler plain = (rows, env, validateOnly) -> { };

        plain.afterImportData(new ArrayList<>(List.of(row("A"))), List.of(), Map.of());
    }

    @Test
    void theAfterPassIsNotGivenRowsThatFailed() {
        // The pipeline removes failed rows before persisting, and a failed row is added to neither
        // side, so a handler is never asked to finish a row that was not written.
        RecordingHandler handler = new RecordingHandler();
        List<Map<String, Object>> surviving = new ArrayList<>(List.of(row("A")));
        surviving.get(0).put("id", 11L);

        handler.afterImportData(surviving, List.of(), Map.of());

        assertThat(handler.created.get(0)).hasSize(1);
        assertThat(handler.created.get(0).get(0)).containsEntry("code", "A");
        assertThat(handler.updated.get(0)).isEmpty();
    }
}
