package io.softa.starter.metadata.scanner.annotation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IndexType;
import io.softa.starter.metadata.entity.SysModelIndex;

/**
 * Derives the trigram indexes that back the product's own search box, so nobody has to declare them.
 *
 * <p><b>Why derive instead of annotate.</b> {@code @Model(searchName = {...})} already names the
 * columns a user types a fragment into: the {@code POST /{modelName}/searchName} endpoint defaults its
 * operator to {@code CONTAINS}, and {@code WhereBuilder.parseSearchName} expands that into
 * {@code (c1 ILIKE ? OR c2 ILIKE ?)}. Those columns are therefore the exact set that needs a
 * substring index on PostgreSQL — and they are knowable at boot. Requiring a second, parallel
 * {@code @Index} declaration on every searchable model would restate information the model already
 * carries, and the two would drift the first time somebody edited one and not the other.
 *
 * <p><b>What it deliberately does NOT do.</b> It never touches a hand-written {@code @Index}. A
 * developer's declaration is a statement of intent about how a column is queried, and "single text
 * column, not unique" is not evidence of substring search — {@code idx_emp_pre_onboarding_invite_token}
 * fits that shape exactly and serves {@code token = ?}, which a GIN index answers worse than the
 * B-tree it would have replaced. Explicit control stays available through {@code @Index(type = ...)}.
 *
 * <p><b>Both lanes must derive identically.</b> The annotation lane calls this from
 * {@code MetadataReadPipeline.parse}; the studio lane calls it while assembling its desired state.
 * Skipping the studio side would not merely lose the indexes there — a studio deploy converges the
 * runtime to exactly the design state, so rows present only in {@code sys_model_index} are DELETEd on
 * every deploy and recreated on every boot, and the aggregate diff never reports "no change" again.
 * That is why the input is this neutral shape rather than {@code SysModel} / {@code SysField}: the two
 * lanes hold different row types but must produce byte-identical output, so they share one
 * implementation instead of two copies of the {@code searchName} resolution rule.
 *
 * <p>Nothing here fails a boot. Every rejection path skips the index and (where it is worth knowing
 * about) logs. These indexes are derived from models nobody annotated for this purpose, so a
 * derivation that could throw would turn an unrelated model's shape into a startup failure.
 */
@Slf4j
public final class SearchIndexSynthesizer {

    /** Same cap as {@code sys_model_index.index_name} — see {@code SysModelIndex.indexName}. */
    private static final int INDEX_NAME_MAX = 60;

    private static final String PREFIX = "idx_";
    private static final String SUFFIX = "_trgm";

    /** Base36 chars of the disambiguating digest kept when a name has to be shortened. */
    private static final int HASH_LEN = 6;

    /** The implicit search column when a model declares no {@code searchName} — mirrors ModelManager. */
    private static final String IMPLICIT_SEARCH_FIELD = "name";

    private SearchIndexSynthesizer() {
    }

    /** One model's derivation input, in whichever lane's terms the caller holds it. */
    public record ModelSpec(String modelName,
                            String tableName,
                            List<String> searchName,
                            boolean projection,
                            boolean rdbms,
                            List<FieldSpec> fields) {
    }

    /** One candidate column. {@code columnName} is only used for the index name, never for the row. */
    public record FieldSpec(String fieldName,
                            String columnName,
                            FieldType fieldType,
                            boolean dynamic) {
    }

    /**
     * Derive the trigram index rows for {@code models}.
     *
     * @param models the models to consider, in any order
     * @param declaredIndexNames every index name already claimed by a hand-written declaration,
     *     across ALL models — a derived name that collides with one is dropped, because a developer's
     *     name always wins and because {@code ModelManager} fails the boot on a duplicate
     * @return the derived rows, ready to append to the from-code index set
     */
    public static List<SysModelIndex> derive(Collection<ModelSpec> models,
                                             Collection<String> declaredIndexNames) {
        // Lower-cased: index names are case-insensitive identifiers on both engines, and the
        // physical snapshot compares them lower-cased too.
        Set<String> taken = new HashSet<>();
        for (String name : declaredIndexNames) {
            if (name != null) {
                taken.add(name.toLowerCase(Locale.ROOT));
            }
        }
        List<SysModelIndex> derived = new ArrayList<>();
        for (ModelSpec model : models) {
            deriveForModel(model, taken, derived);
        }
        return derived;
    }

    private static void deriveForModel(ModelSpec model, Set<String> taken, List<SysModelIndex> out) {
        if (model.projection() || !model.rdbms()) {
            // A projection does not own its table (indexing it would step on the owner), and a
            // non-RDBMS model has no SQL index to speak of.
            return;
        }
        for (FieldSpec field : resolveSearchFields(model)) {
            String indexName = deriveIndexName(model.tableName(), field.columnName());
            if (!taken.add(indexName.toLowerCase(Locale.ROOT))) {
                // Either a developer already owns this name, or two models share a table and would
                // derive the same one. Dropping is right in both cases: a derived index is never
                // worth failing a boot over, and ModelManager would do exactly that on a duplicate.
                log.debug("Search index {} on {} already claimed; skipping derivation",
                        indexName, model.modelName());
                continue;
            }
            SysModelIndex idx = new SysModelIndex();
            idx.setModelName(model.modelName());
            idx.setIndexName(indexName);
            // Field names, not column names: the DDL context builder maps field -> column itself.
            idx.setIndexFields(List.of(field.fieldName()));
            idx.setUniqueIndex(false);
            idx.setIndexType(IndexType.TRIGRAM);
            out.add(idx);
        }
    }

    /**
     * The columns a search actually lands on, resolved the same way {@code ModelManager} resolves
     * them at metadata load: an explicit {@code searchName}, else a field literally called
     * {@code name}, else the id — and the id branch means the model has no text search at all, so it
     * yields nothing here.
     *
     * <p>Non-STRING and dynamic members are dropped rather than rejected. {@code ModelManager}
     * asserts STRING for the explicit branch, but not for the implicit {@code name} one, so this
     * filter is load-bearing: a model with a non-text field called {@code name} is a real shape.
     */
    private static List<FieldSpec> resolveSearchFields(ModelSpec model) {
        List<String> declared = model.searchName();
        List<FieldSpec> resolved = new ArrayList<>();
        if (declared != null && !declared.isEmpty()) {
            for (String fieldName : declared) {
                FieldSpec field = findField(model, fieldName);
                if (isDerivable(field)) {
                    resolved.add(field);
                }
            }
            return resolved;
        }
        FieldSpec implicit = findField(model, IMPLICIT_SEARCH_FIELD);
        if (isDerivable(implicit)) {
            resolved.add(implicit);
        }
        return resolved;
    }

    private static boolean isDerivable(FieldSpec field) {
        return field != null
                && !field.dynamic()
                && field.fieldType() == FieldType.STRING
                && field.columnName() != null
                && !field.columnName().isBlank();
    }

    private static FieldSpec findField(ModelSpec model, String fieldName) {
        for (FieldSpec field : model.fields()) {
            if (field.fieldName().equals(fieldName)) {
                return field;
            }
        }
        return null;
    }

    /**
     * {@code idx_<table>_<column>_trgm}, shortened when that overflows the column width.
     *
     * <p>The parser's policy for an over-length name — reject and make the developer supply a
     * shorter explicit one ({@code AnnotationParser.buildIndex}) — cannot apply here: the whole point
     * is that no developer is involved. Overflow is not hypothetical either; the longest table in a
     * real deployment measured 38 characters, which overflows at a 13-character column.
     *
     * <p>The digest is SHA-256, not {@code String.hashCode()}: the name has to be identical on every
     * boot and every JVM. A name that shifted would create the new index and reap the old one as
     * undeclared on the next boot — perpetual index churn on tables large enough for this to matter.
     */
    static String deriveIndexName(String tableName, String columnName) {
        String base = PREFIX + tableName + "_" + columnName + SUFFIX;
        if (base.length() <= INDEX_NAME_MAX) {
            return base;
        }
        String digest = shortDigest(tableName + "." + columnName);
        // Budget the fixed parts, then split what is left evenly between the two names so neither
        // is erased: a name that kept only the table would collide across that table's own columns.
        int budget = INDEX_NAME_MAX - PREFIX.length() - SUFFIX.length() - digest.length() - 2;
        int tablePart = Math.max(1, budget / 2);
        int columnPart = Math.max(1, budget - tablePart);
        return PREFIX + truncate(tableName, tablePart)
                + "_" + truncate(columnName, columnPart)
                + "_" + digest + SUFFIX;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String shortDigest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; sb.length() < HASH_LEN; i++) {
                sb.append(Character.forDigit((hash[i] & 0xFF) % 36, 36));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
