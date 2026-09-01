package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * What an index is FOR, expressed database-independently. The dialect decides how to render it.
 *
 * <p>This is deliberately a purpose, not a physical access method: {@code GIN}, {@code gin_trgm_ops}
 * and {@code FULLTEXT} are vocabulary of one engine each, and the same declaration has to compile on
 * every database the framework supports. Naming the purpose lets each {@code DdlDialect} pick
 * whatever its engine offers — and lets an engine that offers nothing special fall back to a plain
 * index without the declaration becoming a lie.
 *
 * <p>Rendering today:
 * <table>
 *   <tr><th></th><th>PostgreSQL</th><th>MySQL</th></tr>
 *   <tr><td>{@link #BTREE}</td><td>{@code CREATE INDEX x ON t (c)}</td><td>{@code ADD INDEX x (c)}</td></tr>
 *   <tr><td>{@link #TRIGRAM}</td><td>{@code CREATE INDEX x ON t USING gin (c gin_trgm_ops)}</td>
 *       <td>{@code ADD INDEX x (c)} — identical to BTREE</td></tr>
 * </table>
 *
 * <p>Unset ({@code @Index.type} empty array / {@code sys_model_index.index_type} NULL) reads as
 * {@link #BTREE}, so every index that existed before this enum keeps rendering byte-for-byte as it
 * did.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Index Type")
public enum IndexType {

    /** Ordinary balanced-tree index: equality, ranges, ordering, prefix matching. The default. */
    BTREE("BTree"),

    /**
     * Substring search index, for columns a user types a fragment into.
     *
     * <p>Only meaningful on PostgreSQL, and only there does it change anything: PostgreSQL compares
     * case-insensitively via {@code ILIKE} (see {@code PostgreSQLDialect}), and <b>no</b> B-tree can
     * serve {@code ILIKE} — not even the anchored {@code ILIKE 'x%'} form that a B-tree would happily
     * answer under plain {@code LIKE}. A trigram GIN index answers both the anchored and the
     * unanchored form. MySQL gets a plain index here because its {@code LIKE} already uses B-trees
     * for the anchored form, and {@code FULLTEXT} would not help the unanchored one (it only serves
     * {@code MATCH ... AGAINST}, which the ORM never emits).
     *
     * <p><b>Requires the {@code pg_trgm} extension</b> on PostgreSQL. The framework never creates it
     * — {@code CREATE EXTENSION} generally needs superuser rights that a managed database will not
     * grant — so it is a deployment prerequisite, checked before any such index is rendered.
     *
     * <p>Cannot be combined with {@code unique = true}: GIN has no uniqueness support and PostgreSQL
     * rejects the combination outright.
     */
    TRIGRAM("Trigram");

    @JsonValue
    private final String code;
}
