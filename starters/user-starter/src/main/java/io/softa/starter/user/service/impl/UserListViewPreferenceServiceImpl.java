package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.dto.UserListViewPreferenceDTO;
import io.softa.starter.user.entity.UserListViewPreference;
import io.softa.starter.user.service.UserListViewPreferenceService;

@Service
public class UserListViewPreferenceServiceImpl extends EntityServiceImpl<UserListViewPreference, Long>
        implements UserListViewPreferenceService {

    static final String PART_COLUMNS = "columns";
    static final String PART_EXPORT = "export";
    static final String PART_SETTINGS = "settings";

    /** Generous for any real list, small enough that a row cannot be used as storage. */
    static final int MAX_NAMES = 500;
    static final int MAX_NAME_LENGTH = 128;
    static final int MAX_VIEW_STATE_KEY_LENGTH = 255;
    /** View settings are a handful of flags; this is ample, and keeps a row from becoming storage. */
    static final int MAX_SETTINGS_LENGTH = 4000;

    private static final String MODEL = "UserListViewPreference";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, List<String>>> STRING_LIST_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    @Autowired
    private PermissionService permissionService;

    @Override
    public UserListViewPreferenceDTO getMine(String viewStateKey) {
        requireViewStateKey(viewStateKey);
        UserListViewPreference row = asSystem(() -> findMine(viewStateKey));
        // Filtered in the caller's own context, not the system's: under a skipped permission check
        // every field reads as allowed, which is exactly the pass this filter exists to deny.
        return row == null ? null : readable(toDto(row));
    }

    /**
     * Not one transaction, on purpose. Two parts of a list can save their first arrangement at once —
     * the side tree and the columns on the same page load — and both find no row and create one. The
     * loser meets the unique index; in its own statement it can then find the winner's row and save
     * over it, which inside a shared transaction the database would no longer allow.
     */
    @Override
    public UserListViewPreferenceDTO saveMine(UserListViewPreferenceDTO preference) {
        if (preference == null) {
            throw new BusinessException("Nothing to save.");
        }
        requireViewStateKey(preference.getViewStateKey());
        String model = preference.getModelName();
        if (model == null || !ModelManager.existModel(model)) {
            throw new BusinessException("Unknown model: " + model);
        }
        // Only what the person may read is accepted from them: a name they sent but cannot see is not
        // theirs to store.
        UserListViewPreferenceDTO incoming = readable(bounded(preference));
        // What the caller cannot see right now is still theirs: a column dropped by a withdrawn grant or
        // a country no longer managed comes back where it was when they can see it again. Worked out
        // here, in the caller's own context — inside asSystem every field reads as visible, and nothing
        // would ever look unseen.
        UserListViewPreference stored = asSystem(() -> findMine(incoming.getViewStateKey()));
        if (stored != null) {
            keepUnseen(incoming, toDto(stored), name -> isUnseen(model, name));
        }
        Long me = currentUserId();
        asSystem(() -> {
            UserListViewPreference existing = findMine(incoming.getViewStateKey());
            if (existing != null) {
                modelService.updateOne(MODEL, changes(existing, incoming));
                return null;
            }
            UserListViewPreference created = new UserListViewPreference();
            created.setUserId(me);
            created.setViewStateKey(incoming.getViewStateKey());
            try {
                this.createOne(applied(created, incoming));
            } catch (RuntimeException raced) {
                UserListViewPreference winner = findMine(incoming.getViewStateKey());
                if (winner == null) {
                    throw raced;
                }
                modelService.updateOne(MODEL, changes(winner, incoming));
            }
            return null;
        });
        return getMine(incoming.getViewStateKey());
    }

    @Override
    public void resetMine(String viewStateKey, String part) {
        requireViewStateKey(viewStateKey);
        boolean columns = part == null || PART_COLUMNS.equals(part);
        boolean export = part == null || PART_EXPORT.equals(part);
        boolean settings = part == null || PART_SETTINGS.equals(part);
        if (!columns && !export && !settings) {
            throw new BusinessException("Unknown part: " + part);
        }
        asSystem(() -> {
            UserListViewPreference row = findMine(viewStateKey);
            if (row == null) {
                return null;
            }
            // Only the parts being reset are written: writing the whole row back would put the other
            // parts as this request read them over a save that landed in between.
            Map<String, Object> cleared = new LinkedHashMap<>();
            cleared.put("id", row.getId());
            if (columns) {
                cleared.put("columnOrder", null);
                cleared.put("hiddenColumns", null);
                cleared.put("addedColumns", null);
                cleared.put("freezeColumnIndex", null);
            }
            if (export) {
                cleared.put("exportFields", null);
                cleared.put("exportObjectFields", null);
            }
            if (settings) {
                cleared.put("viewSettings", null);
            }
            if (columns) {
                row.setColumnOrder(null);
                row.setHiddenColumns(null);
                row.setAddedColumns(null);
                row.setFreezeColumnIndex(null);
            }
            if (export) {
                row.setExportFields(null);
                row.setExportObjectFields(null);
            }
            if (settings) {
                row.setViewSettings(null);
            }
            if (isEmpty(row)) {
                this.deleteById(row.getId());
            } else {
                modelService.updateOne(MODEL, cleared);
            }
            return null;
        });
    }

    /** The caller's row for a list. Tenant is narrowed by the framework; the account by this filter. */
    private UserListViewPreference findMine(String viewStateKey) {
        List<UserListViewPreference> rows = this.searchList(new Filters()
                .eq(UserListViewPreference::getUserId, currentUserId())
                .eq(UserListViewPreference::getViewStateKey, viewStateKey));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * The update for a stored row: its id and only the parts {@code incoming} carries. A part it omits
     * is not written at all — written back as this request read it, it would undo a save of that part
     * made in between (the side tree and the columns of one page save at once). View settings merge
     * key by key into the stored ones; two saves of different keys at the same instant can still race.
     */
    static Map<String, Object> changes(UserListViewPreference existing, UserListViewPreferenceDTO incoming) {
        UserListViewPreference patch = new UserListViewPreference();
        patch.setViewSettings(existing.getViewSettings());
        applied(patch, incoming);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", existing.getId());
        row.put("modelName", patch.getModelName());
        if (incoming.getColumnOrder() != null) row.put("columnOrder", patch.getColumnOrder());
        if (incoming.getHiddenColumns() != null) row.put("hiddenColumns", patch.getHiddenColumns());
        if (incoming.getAddedColumns() != null) row.put("addedColumns", patch.getAddedColumns());
        if (incoming.getFreezeColumnIndex() != null) row.put("freezeColumnIndex", patch.getFreezeColumnIndex());
        if (incoming.getExportFields() != null) row.put("exportFields", patch.getExportFields());
        if (incoming.getExportObjectFields() != null) row.put("exportObjectFields", patch.getExportObjectFields());
        if (incoming.getViewSettings() != null) row.put("viewSettings", patch.getViewSettings());
        return row;
    }

    /** {@code row} with the parts {@code incoming} carries laid over it; the parts it omits kept. */
    private static UserListViewPreference applied(UserListViewPreference row, UserListViewPreferenceDTO incoming) {
        row.setModelName(incoming.getModelName());
        if (incoming.getColumnOrder() != null) row.setColumnOrder(JsonUtils.objectToString(incoming.getColumnOrder()));
        if (incoming.getHiddenColumns() != null) row.setHiddenColumns(JsonUtils.objectToString(incoming.getHiddenColumns()));
        if (incoming.getAddedColumns() != null) row.setAddedColumns(JsonUtils.objectToString(incoming.getAddedColumns()));
        if (incoming.getFreezeColumnIndex() != null) row.setFreezeColumnIndex(Math.max(0, incoming.getFreezeColumnIndex()));
        if (incoming.getExportFields() != null) row.setExportFields(JsonUtils.objectToString(incoming.getExportFields()));
        if (incoming.getExportObjectFields() != null) {
            row.setExportObjectFields(JsonUtils.objectToString(incoming.getExportObjectFields()));
        }
        if (incoming.getViewSettings() != null) {
            row.setViewSettings(mergeSettings(row.getViewSettings(), incoming.getViewSettings()));
        }
        return row;
    }

    private static boolean isEmpty(UserListViewPreference row) {
        return row.getColumnOrder() == null && row.getHiddenColumns() == null && row.getAddedColumns() == null
                && row.getFreezeColumnIndex() == null
                && row.getExportFields() == null && row.getExportObjectFields() == null
                && row.getViewSettings() == null;
    }

    /**
     * Drop every field the caller may not see. The list's own fields are checked against its model;
     * each object's fields against the model that object is; a column path one level into a one-to-one
     * record against both — the link on the list's model, the field on the record's.
     *
     * <p>"See" is two things for a column: readable under the caller's sensitive sets, and — for a
     * field that only some countries use — one of the caller's countries. The export lists keep the
     * readability check alone for the model's own fields; a field of a one-to-one record is judged
     * as a column on that record is.
     */
    UserListViewPreferenceDTO readable(UserListViewPreferenceDTO dto) {
        String model = dto.getModelName();
        if (model == null || !ModelManager.existModel(model)) {
            return dto;
        }
        dto.setColumnOrder(visibleColumns(model, dto.getColumnOrder()));
        dto.setHiddenColumns(visibleColumns(model, dto.getHiddenColumns()));
        dto.setAddedColumns(visibleAdded(model, dto.getAddedColumns()));
        dto.setExportFields(exportable(model, dto.getExportFields()));
        Map<String, List<String>> objects = dto.getExportObjectFields();
        if (objects != null) {
            Map<String, List<String>> kept = new LinkedHashMap<>();
            for (String collection : readableOf(model, new ArrayList<>(objects.keySet()))) {
                String related = relatedModelOf(model, collection);
                if (related != null) {
                    // An object's sheet lists the object's own fields; a path into another record is not one.
                    List<String> own = objects.get(collection) == null ? null
                            : objects.get(collection).stream().filter(name -> name.indexOf('.') < 0).toList();
                    kept.put(collection, readableOf(related, own));
                }
            }
            dto.setExportObjectFields(kept);
        }
        return dto;
    }

    /**
     * Export fields the caller may still read: the model's own by the readability check, and a field
     * of a one-to-one child ({@code employeeProfileId.passType}) by the child's own — the model's
     * check knows nothing of a path, and would let one through whatever the child's sets say.
     */
    private List<String> exportable(String model, List<String> names) {
        if (names == null) {
            return null;
        }
        List<String> plain = names.stream().filter(name -> name.indexOf('.') < 0).toList();
        Set<String> readable = Set.copyOf(readableOf(model, plain));
        List<String> out = new ArrayList<>(names.size());
        for (String name : names) {
            boolean keep = name.indexOf('.') < 0 ? readable.contains(name) : stateOf(model, name) == PathState.VISIBLE;
            if (keep) out.add(name);
        }
        return out;
    }

    private List<String> readableOf(String model, List<String> names) {
        if (names == null) {
            return null;
        }
        Collection<String> readable = permissionService.filterReadableFields(model, names, AccessType.READ);
        return readable == null ? names : new ArrayList<>(readable);
    }

    /** Where a column path stands for the caller. */
    enum PathState {
        /** Resolves, and the caller may see it. */
        VISIBLE,
        /** Resolves, but the caller may not see it right now — kept for when they can again. */
        UNSEEN,
        /** Names nothing the list can show: a deleted field, a link that is not one-to-one, two levels. */
        GONE
    }

    /**
     * Where {@code path} stands: a field of {@code model}, or {@code link.field} one level into a
     * one-to-one record. Deeper paths and any other kind of link name no column the list can show.
     */
    PathState stateOf(String model, String path) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            MetaField field = ModelManager.getModelFieldOrNull(model, path);
            if (field == null) {
                return PathState.GONE;
            }
            return seen(model, path, field) ? PathState.VISIBLE : PathState.UNSEEN;
        }
        if (path.indexOf('.', dot + 1) >= 0) {
            return PathState.GONE;
        }
        String linkName = path.substring(0, dot);
        String leafName = path.substring(dot + 1);
        MetaField link = ModelManager.getModelFieldOrNull(model, linkName);
        if (link == null || link.getFieldType() != FieldType.ONE_TO_ONE || link.getRelatedModel() == null) {
            return PathState.GONE;
        }
        String related = link.getRelatedModel();
        MetaField leaf = ModelManager.getModelFieldOrNull(related, leafName);
        if (leaf == null) {
            return PathState.GONE;
        }
        return seen(model, linkName, link) && seen(related, leafName, leaf) ? PathState.VISIBLE : PathState.UNSEEN;
    }

    /**
     * The column ids of the order or the hidden list the caller may see. A plain id that is not a field
     * is kept as it is — a list can have columns of its own (a pivot column, say) that only the list
     * can judge — and a plain field id goes through the readability check as one batch.
     */
    private List<String> visibleColumns(String model, List<String> ids) {
        if (ids == null) {
            return null;
        }
        List<String> plain = ids.stream().filter(id -> id.indexOf('.') < 0).toList();
        Set<String> readable = Set.copyOf(readableOf(model, plain));
        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id.indexOf('.') >= 0) {
                if (stateOf(model, id) == PathState.VISIBLE) out.add(id);
                continue;
            }
            MetaField field = ModelManager.getModelFieldOrNull(model, id);
            if (readable.contains(id) && (field == null || inMyCountries(field))) out.add(id);
        }
        return out;
    }

    /** Added columns are always field paths, so each must still name a field the caller may see. */
    private List<String> visibleAdded(String model, List<String> paths) {
        if (paths == null) {
            return null;
        }
        return paths.stream().filter(path -> stateOf(model, path) == PathState.VISIBLE).toList();
    }

    /** Whether a stored column is still a column, just not one the caller can see right now. */
    private boolean isUnseen(String model, String id) {
        return stateOf(model, id) == PathState.UNSEEN;
    }

    private boolean seen(String model, String fieldName, MetaField field) {
        Collection<String> readable = permissionService.filterReadableFields(model, List.of(fieldName), AccessType.READ);
        return (readable == null || readable.contains(fieldName)) && inMyCountries(field);
    }

    /**
     * Whether a field applies to one of the caller's countries. A field no country is named on applies
     * everywhere; a caller whose countries are unknown or none is not narrowed — the same reading the
     * per-country narrowing of value lists makes, so a column and its dropdown never disagree.
     */
    static boolean inMyCountries(MetaField field) {
        List<String> fieldCountries = field.getCountries();
        if (fieldCountries == null || fieldCountries.isEmpty()) {
            return true;
        }
        Set<String> mine = ContextHolder.getContext().getGrantedCountries();
        if (mine == null || mine.isEmpty()) {
            return true;
        }
        return fieldCountries.stream().anyMatch(mine::contains);
    }

    /**
     * Put back into {@code incoming}'s columns what is stored but unseen. The incoming lists hold only
     * what the caller could see, so an absent column is either one they removed or one they could not
     * see; only the second is kept. A column that no longer exists is neither, and goes.
     */
    static void keepUnseen(UserListViewPreferenceDTO incoming, UserListViewPreferenceDTO stored, Predicate<String> unseen) {
        if (incoming.getColumnOrder() != null) {
            incoming.setColumnOrder(reinserted(stored.getColumnOrder(), incoming.getColumnOrder(), unseen));
        }
        if (incoming.getHiddenColumns() != null) {
            incoming.setHiddenColumns(kept(stored.getHiddenColumns(), incoming.getHiddenColumns(), unseen));
        }
        if (incoming.getAddedColumns() != null) {
            incoming.setAddedColumns(kept(stored.getAddedColumns(), incoming.getAddedColumns(), unseen));
        }
        // An export field is a column of the sheet: one the caller cannot see now keeps its place too.
        if (incoming.getExportFields() != null) {
            incoming.setExportFields(reinserted(stored.getExportFields(), incoming.getExportFields(), unseen));
        }
    }

    /**
     * {@code incoming} with each unseen stored column put back after the column it followed — the
     * nearest earlier stored column that is in the result — or first when none is. Walked in stored
     * order, so a run of unseen columns keeps its own order.
     */
    static List<String> reinserted(List<String> stored, List<String> incoming, Predicate<String> unseen) {
        List<String> out = new ArrayList<>(incoming);
        if (stored == null) {
            return out;
        }
        for (int i = 0; i < stored.size(); i++) {
            String id = stored.get(i);
            if (out.contains(id) || !unseen.test(id)) {
                continue;
            }
            int at = 0;
            for (int j = i - 1; j >= 0; j--) {
                int k = out.indexOf(stored.get(j));
                if (k >= 0) {
                    at = k + 1;
                    break;
                }
            }
            out.add(at, id);
        }
        return out;
    }

    /** {@code incoming} plus the unseen stored entries it does not hold. */
    static List<String> kept(List<String> stored, List<String> incoming, Predicate<String> unseen) {
        List<String> out = new ArrayList<>(incoming);
        if (stored != null) {
            stored.stream().filter(id -> !out.contains(id) && unseen.test(id)).forEach(out::add);
        }
        return out;
    }

    private static String relatedModelOf(String model, String field) {
        if (!ModelManager.existField(model, field)) {
            return null;
        }
        MetaField meta = ModelManager.getModelField(model, field);
        return meta == null ? null : meta.getRelatedModel();
    }

    /**
     * The stored settings with {@code incoming} laid over them key by key; a key sent as null is
     * removed. Null when nothing is left, so an emptied row can be deleted.
     */
    static String mergeSettings(String stored, Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (stored != null) {
            Map<String, Object> existing = parsed(stored, OBJECT_MAP);
            if (existing != null) merged.putAll(existing);
        }
        incoming.forEach((key, value) -> {
            if (value == null) merged.remove(key);
            else merged.put(key, value);
        });
        if (merged.isEmpty()) {
            return null;
        }
        String json = JsonUtils.objectToString(merged);
        if (json.length() > MAX_SETTINGS_LENGTH) {
            throw new BusinessException("The list's view settings are too large.");
        }
        return json;
    }

    /** Refuse what no real list produces, so a row cannot be used to store anything else. */
    private static UserListViewPreferenceDTO bounded(UserListViewPreferenceDTO dto) {
        checkNames(dto.getColumnOrder());
        checkNames(dto.getHiddenColumns());
        checkNames(dto.getAddedColumns());
        checkNames(dto.getExportFields());
        if (dto.getViewSettings() != null) {
            dto.getViewSettings().keySet().forEach(UserListViewPreferenceServiceImpl::checkName);
        }
        if (dto.getExportObjectFields() != null) {
            if (dto.getExportObjectFields().size() > MAX_NAMES) {
                throw new BusinessException("Too many objects in the arrangement.");
            }
            dto.getExportObjectFields().forEach((key, value) -> {
                checkName(key);
                checkNames(value);
            });
        }
        return dto;
    }

    private static void checkNames(List<String> names) {
        if (names == null) {
            return;
        }
        if (names.size() > MAX_NAMES) {
            throw new BusinessException("Too many fields in the arrangement.");
        }
        names.forEach(UserListViewPreferenceServiceImpl::checkName);
    }

    private static void checkName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException("Invalid field name in the arrangement.");
        }
    }

    static UserListViewPreferenceDTO toDto(UserListViewPreference row) {
        UserListViewPreferenceDTO dto = new UserListViewPreferenceDTO();
        dto.setViewStateKey(row.getViewStateKey());
        dto.setModelName(row.getModelName());
        dto.setColumnOrder(parseList(row.getColumnOrder()));
        dto.setHiddenColumns(parseList(row.getHiddenColumns()));
        dto.setAddedColumns(parseList(row.getAddedColumns()));
        dto.setFreezeColumnIndex(row.getFreezeColumnIndex());
        dto.setExportFields(parseList(row.getExportFields()));
        dto.setExportObjectFields(parsed(row.getExportObjectFields(), STRING_LIST_MAP));
        dto.setViewSettings(parsed(row.getViewSettings(), OBJECT_MAP));
        return dto;
    }

    private static List<String> parseList(String json) {
        return parsed(json, STRING_LIST);
    }

    /**
     * A stored part, or null when it does not parse: one damaged part reads as not arranged, rather
     * than locking the person out of the list until someone resets it.
     */
    private static <T> T parsed(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return JsonUtils.stringToObject(json, type, null);
        } catch (RuntimeException damaged) {
            return null;
        }
    }

    private static void requireViewStateKey(String viewStateKey) {
        if (viewStateKey == null || viewStateKey.isBlank() || viewStateKey.length() > MAX_VIEW_STATE_KEY_LENGTH) {
            throw new BusinessException("A list key is required.");
        }
    }

    private static Long currentUserId() {
        Long id = ContextHolder.getContext().getUserId();
        if (id == null) {
            throw new BusinessException("Sign in to keep a list arrangement.");
        }
        return id;
    }

    /**
     * Past the row scope: the model has no anchor a role could be scoped by, so an ordinary read
     * would fail closed. {@link #findMine} narrows to the caller's own rows instead.
     */
    private static <R> R asSystem(Supplier<R> action) {
        Context ctx = ContextHolder.cloneContext();
        ctx.setSkipPermissionCheck(true);
        return ContextHolder.callWith(ctx, action::get);
    }
}
