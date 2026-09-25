package io.softa.starter.metadata.catalog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.dto.DTOFieldObject;
import io.softa.framework.orm.enums.FieldType;

/**
 * Single source of truth for {@code sys_*} persistence, built once per entity
 * by reflecting the entity's own {@code @Model} / {@code @Field} annotations
 * (the same annotations the scanner already manages).
 *
 * <p>The resulting {@link SysTable} drives equality (DiffEngine),
 * INSERT/UPDATE/DELETE (SysJdbcWriter) and SELECT + row-mapping
 * (SysJdbcLoader). Adding a {@code sys_field} attribute therefore means adding
 * a single {@code @Field} to the entity — all three layers follow.
 *
 * <p>Built from compile-time annotations on the classes (never from DB rows),
 * so the "SysModel describes SysModel" self-reference stays non-circular.
 */
public final class SysCatalog {

    private SysCatalog() {}

    /**
     * Fields carrying {@code @Field} that are <b>not</b> annotation-derived
     * data, so they never enter the diff / UPDATE SET:
     * <ul>
     *   <li>{@code active} / {@code deleted} — runtime active-control state, not
     *       managed by the annotation scanner.</li>
     *   <li>{@code modelId} / {@code optionSetId} — surrogate FKs to the owning
     *       model / option set. The parser cannot set them (the parent's surrogate id is
     *       DB-assigned), so they are resolved post-scan from {@code modelName} /
     *       {@code optionSetCode} (see {@code SysReferenceSql}). Excluding them keeps the
     *       diff from seeing a perpetual {@code null}(from-code)≠{@code <id>}(from-db) MODIFY
     *       that would wipe the populated value on every boot.</li>
     * </ul>
     *
     * <p>{@code id} and {@code appCode} are also excluded from the compared/written
     * data columns, but are captured as dedicated {@link SysTable#idColumn()} /
     * {@link SysTable#appCodeColumn()} accessors so the loader can read them and the
     * writer can target a row by {@code id} in a declared-rename UPDATE.
     */
    private static final Set<String> EXCLUDED =
            Set.of("active", "deleted", "modelId", "optionSetId", "renamedFrom");

    private static final Map<Class<?>, SysTable<?>> CACHE = new ConcurrentHashMap<>();

    /**
     * The catalog entities the boot pipeline must be able to read <b>before</b> any
     * catalog-row diff can run — exactly the tables {@code SysJdbcLoader.doLoad()}
     * SELECTs. The scanner physically reconciles these tables from their own
     * annotations first ({@code DdlOrchestrator.reconcilePhysical}), which is what
     * lets a fresh database bootstrap itself and an existing one absorb additive
     * catalog-schema changes without a hand-written migration. Keep the two lists
     * in lockstep: a new entity in the loader's read set MUST be added here, or
     * its table won't exist when the loader first reads it.
     */
    public static final List<Class<?>> BOOT_READ_ENTITIES = List.of(
            io.softa.starter.metadata.entity.SysModel.class,
            io.softa.starter.metadata.entity.SysField.class,
            io.softa.starter.metadata.entity.SysOptionSet.class,
            io.softa.starter.metadata.entity.SysOptionItem.class,
            io.softa.starter.metadata.entity.SysModelIndex.class);

    @SuppressWarnings("unchecked")
    public static <E> SysTable<E> of(Class<E> entity) {
        return (SysTable<E>) CACHE.computeIfAbsent(entity, SysCatalog::describe);
    }

    private static <E> SysTable<E> describe(Class<E> entity) {
        Model model = entity.getAnnotation(Model.class);
        if (model == null) {
            throw new IllegalStateException(entity.getName() + " is not annotated with @Model");
        }
        String table = StringUtils.isBlank(model.tableName())
                ? StringTools.toUnderscoreCase(entity.getSimpleName())
                : model.tableName();
        Set<String> keyNames = Set.of(model.businessKey());

        List<SysColumn<E>> keys = new ArrayList<>();
        List<SysColumn<E>> data = new ArrayList<>();
        SysColumn<E> appCodeColumn = null;
        SysColumn<E> idColumn = null;

        for (Field f : entity.getDeclaredFields()) {
            io.softa.framework.orm.annotation.Field ann =
                    f.getAnnotation(io.softa.framework.orm.annotation.Field.class);
            if (ann == null) {
                continue;   // not a column (e.g. id, un-annotated in-memory fields)
            }
            // X-to-many relations (ONE_TO_MANY / MANY_TO_MANY) and dynamic fields
            // have no physical column → never persisted as a sys_* column (they
            // still become sys_field ROWS via the parser). Mirrors
            // SysDdlContextBuilder.isPhysicalColumn (skip dynamic + TO_MANY).
            FieldType[] explicitType = ann.fieldType();
            if (ann.dynamic()
                    || (explicitType.length > 0 && FieldType.TO_MANY_TYPES.contains(explicitType[0]))) {
                continue;
            }
            String name = f.getName();
            String column = StringUtils.isBlank(ann.columnName())
                    ? StringTools.toUnderscoreCase(name)
                    : ann.columnName();

            if ("id".equals(name)) {
                idColumn = new SysColumn<>(name, column, getter(entity, f), setter(entity, f), codecFor(f.getType()));
                continue;
            }
            if ("appCode".equals(name)) {
                appCodeColumn = new SysColumn<>(name, column, getter(entity, f), setter(entity, f), Codecs.STRING);
                continue;
            }
            if (EXCLUDED.contains(name)) {
                continue;              // active / deleted / surrogate FKs / renamedFrom
            }
            SysColumn<E> col = new SysColumn<>(name, column, getter(entity, f), setter(entity, f), codecFor(f.getType()));
            (keyNames.contains(name) ? keys : data).add(col);
        }
        return new SysTable<>(table, keys, data, appCodeColumn, idColumn);
    }

    private static Codec codecFor(Class<?> type) {
        if (type == String.class) return Codecs.STRING;
        if (type == Integer.class) return Codecs.INT;
        if (type == Long.class) return Codecs.LONG;
        if (type == Boolean.class) return Codecs.BOOL;
        if (type.isEnum()) return Codecs.enumCodec(type);
        if (List.class.isAssignableFrom(type)) return Codecs.STRING_LIST;
        if (type == Orders.class) return Codecs.ORDERS;
        if (DTOFieldObject.class.isAssignableFrom(type)) return Codecs.dto(type);
        throw new IllegalStateException("No Codec for @Field type " + type.getName());
    }

    private static <E> Function<E, Object> getter(Class<E> entity, Field f) {
        Method m = accessor(entity, "get" + cap(f.getName()));
        return e -> {
            try {
                return m.invoke(e);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(
                        "getter failed: " + entity.getSimpleName() + "." + f.getName(), ex);
            }
        };
    }

    private static <E> BiConsumer<E, Object> setter(Class<E> entity, Field f) {
        Method m = accessor(entity, "set" + cap(f.getName()), f.getType());
        return (e, v) -> {
            try {
                m.invoke(e, v);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(
                        "setter failed: " + entity.getSimpleName() + "." + f.getName(), ex);
            }
        };
    }

    private static Method accessor(Class<?> entity, String name, Class<?>... params) {
        try {
            return entity.getMethod(name, params);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("No accessor " + name + " on " + entity.getName(), ex);
        }
    }

    private static String cap(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Reflected persistence descriptor for one {@code sys_*} table.
     *
     * @param <E> the entity type
     */
    public static final class SysTable<E> {

        private final String table;
        private final List<SysColumn<E>> keys;
        private final List<SysColumn<E>> data;
        private final SysColumn<E> appCodeColumn;
        private final SysColumn<E> idColumn;

        SysTable(String table, List<SysColumn<E>> keys, List<SysColumn<E>> data,
                 SysColumn<E> appCodeColumn, SysColumn<E> idColumn) {
            this.table = table;
            this.keys = List.copyOf(keys);
            this.data = List.copyOf(data);
            this.appCodeColumn = appCodeColumn;
            this.idColumn = idColumn;
        }

        /** Physical table name. */
        public String table() {
            return table;
        }

        /** Natural-key columns (from {@code @Model.businessKey}). */
        public List<SysColumn<E>> keys() {
            return keys;
        }

        /** Annotation-derived data columns (everything compared / updated). */
        public List<SysColumn<E>> data() {
            return data;
        }

        /**
         * The {@code app_code} identity column (stamped server-side on INSERT,
         * not compared), or null.
         */
        public SysColumn<E> appCodeColumn() {
            return appCodeColumn;
        }

        /**
         * The surrogate {@code id} column. Excluded from {@link #keys()} /
         * {@link #data()} (never diffed or written in the SET), but exposed so the
         * loader can populate it and the writer can target a row by {@code id} in a
         * declared-rename UPDATE. Null only if the entity declares no {@code id}.
         */
        public SysColumn<E> idColumn() {
            return idColumn;
        }
    }
}
