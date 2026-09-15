package io.softa.starter.metadata.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.domain.Orders;
import io.softa.starter.metadata.scanner.annotation.inference.JsonValueDeserializer;
import io.softa.starter.metadata.scanner.annotation.inference.JsonValueResolver;

/**
 * Reusable {@link Codec}s. Each delegates to exactly the same conversion the
 * hand-written {@code SysJdbcWriter} / {@code SysJdbcLoader} used, so the
 * descriptor-driven path is behavior-preserving.
 */
public final class Codecs {

    private Codecs() {}

    /** {@code String} &harr; {@code VARCHAR/TEXT}. */
    public static final Codec STRING = new Codec() {
        @Override public Object toDb(Object t) { return t; }
        @Override public Object fromDb(ResultSet rs, String c) throws SQLException { return rs.getString(c); }
    };

    /** {@code Integer} &harr; {@code INT} (null-aware). */
    public static final Codec INT = new Codec() {
        @Override public Object toDb(Object t) { return t; }
        @Override public Object fromDb(ResultSet rs, String c) throws SQLException {
            int v = rs.getInt(c);
            return rs.wasNull() ? null : v;
        }
    };

    /** {@code Long} &harr; {@code BIGINT} (null-aware). */
    public static final Codec LONG = new Codec() {
        @Override public Object toDb(Object t) { return t; }
        @Override public Object fromDb(ResultSet rs, String c) throws SQLException {
            long v = rs.getLong(c);
            return rs.wasNull() ? null : v;
        }
    };

    /**
     * {@code Boolean} &harr; boolean-ish column (null-aware). Binds the {@code Boolean}
     * as-is: the driver maps it onto MySQL {@code TINYINT(1)} and PostgreSQL
     * {@code BOOLEAN} alike, whereas a hand-converted 0/1 {@code Integer} bind is
     * rejected by PostgreSQL's typed BOOLEAN columns.
     */
    public static final Codec BOOL = new Codec() {
        @Override public Object toDb(Object t) { return t; }
        @Override public Object fromDb(ResultSet rs, String c) throws SQLException {
            boolean v = rs.getBoolean(c);
            return rs.wasNull() ? null : v;
        }
    };

    /** {@code List<String>} &harr; comma-joined string; blank/empty &harr; null. */
    public static final Codec STRING_LIST = new Codec() {
        @Override @SuppressWarnings("unchecked")
        public Object toDb(Object t) { return StringTools.listToString((List<String>) t); }
        @Override public Object fromDb(ResultSet rs, String c) throws SQLException {
            String s = rs.getString(c);
            return StringUtils.isBlank(s) ? null : StringTools.stringToList(s);
        }
    };

    /** {@code Orders} &harr; its string form (e.g. "name ASC, seq DESC"). */
    public static final Codec ORDERS = new Codec() {
        @Override public Object toDb(Object t) {
            Orders o = (Orders) t;
            return (o == null || o.isEmpty()) ? null : o.toString();
        }
        @Override public Object fromDb(ResultSet rs, String c) throws SQLException {
            String s = rs.getString(c);
            return StringUtils.isBlank(s) ? null : Orders.of(s);
        }
        @Override public boolean typedEquals(Object a, Object b) {
            // Orders has no equals(); compare by string form (both-null equal).
            String sa = a == null ? null : a.toString();
            String sb = b == null ? null : b.toString();
            return Objects.equals(sa, sb);
        }
    };

    /**
     * A {@code DTOFieldObject} &harr; its canonical JSON text ({@link CanonicalJson}): keys sorted,
     * nulls dropped, an empty object stored as NULL. Read back into the declared class — the entity
     * field's own type, so {@code sys_field.constraints} loads as a {@code FieldConstraints}.
     * Equality is equality of the canonical text: two declarations that mean the same thing never
     * register as a change however they were spelled.
     */
    public static Codec dto(Class<?> type) {
        return new Codec() {
            @Override public Object toDb(Object t) { return CanonicalJson.of(t); }
            @Override public Object fromDb(ResultSet rs, String c) throws SQLException {
                String s = rs.getString(c);
                return StringUtils.isBlank(s) ? null : JsonUtils.stringToObject(s, type);
            }
            @Override public boolean typedEquals(Object a, Object b) {
                return Objects.equals(CanonicalJson.of(a), CanonicalJson.of(b));
            }
        };
    }

    /**
     * {@code Enum} &harr; its {@code @JsonValue} item-code string. Reuses the
     * same resolver/deserializer the hand-written code used, so e.g.
     * {@code IdStrategy.DISTRIBUTED_LONG} round-trips via "DistributedLong",
     * never {@code name()}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Codec enumCodec(Class<?> enumType) {
        return new Codec() {
            @Override public Object toDb(Object t) {
                return t == null ? null : JsonValueResolver.resolveItemCode((Enum<?>) t);
            }
            @Override public Object fromDb(ResultSet rs, String c) throws SQLException {
                return JsonValueDeserializer.fromString((Class) enumType, rs.getString(c));
            }
        };
    }
}
