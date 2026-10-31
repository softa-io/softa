package io.softa.starter.file.excel.imports;

import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.filter.context.CompanyCountryResolver;
import io.softa.starter.file.dto.ImportFieldDTO;

/**
 * Resolves dotted-path relation lookup fields (e.g. deptId.code)
 * by looking up the related model's business key and writing back the real FK id.
 *
 * <p>Design semantics:
 * <ul>
 *   <li>{@code deptId} — direct FK id import (no lookup needed)</li>
 *   <li>{@code deptId.code} — use Department.code to reverse-lookup, write back deptId</li>
 * </ul>
 *
 * <p><b>OneToOne is not a lookup.</b> A ManyToOne / to-many root points at a row that already exists
 * and is shared, so its dotted columns are a business key to search by. A OneToOne root is the main
 * row's own 1:1 sub-record, so its dotted columns are the sub-record's <i>content</i>: they are folded
 * into a nested value object and written inline by the ORM cascade
 * ({@code XToOneGroupProcessor#processNestedOneToOneRows}) — see {@link #resolveNestedOneToOneGroup}.
 *
 * <p><b>Country-partitioned targets are looked up in the row's own country.</b> "Full Time" names a
 * different Employment Type in every country that has one, so a business key on a
 * {@code multiCountry} model is only unique within a country. The importer's own country set is the
 * wrong domain — an HR working across Singapore and New Zealand imports both countries' employees
 * from one file — so each row is resolved in the country of the company <i>it</i> names: the row's
 * company reference (the importing model's field onto the company model — {@code legalEntityId} on
 * an employee, {@code companyId} on a department) is resolved first, turned into a country, and the
 * rows bucketed by it; each bucket queries with {@code country = X} on top of the field's own filters.
 * A row naming no company falls into an unbucketed group and is resolved as before, within the
 * caller's country set. See {@link #countryOfRow}.
 *
 * <p>Rules:
 * <ul>
 *   <li>A fieldName containing a dot whose root field is a relation field is treated as a relation
 *       lookup (ManyToOne / to-many) or a nested sub-record (OneToOne).</li>
 *   <li>Only one level of cascade is supported: {@code deptId.code} is OK, {@code deptId.companyId.code} is NOT.</li>
 *   <li>A direct FK field (e.g. {@code deptId}) and a lookup field (e.g. {@code deptId.code}) must not coexist in the same template.</li>
 * </ul>
 */
@Slf4j
@Component
public class RelationLookupResolver {

    @Autowired
    private ModelService<?> modelService;
    /**
     * Optional: an application with no company model has no row country to bucket by, and the unit
     * tests construct this class by hand. Absent, every lookup runs unbucketed, as it always did.
     */
    @Autowired(required = false)
    private CompanyCountryResolver companyCountryResolver;

    /**
     * Describes a group of dotted-path lookup fields sharing the same root FK field.
     * For example, deptId.code and deptId.name would form one group with rootField="deptId".
     *
     * @param rootField the relation field in the main model, for example {@code deptId} or {@code roleIds}
     * @param relatedModel the related model name used for reverse lookup
     * @param lookupFields the business-key fields in the related model, for example {@code ["code"]}
     * @param dottedPaths the original template field names, for example {@code ["deptId.code"]}
     * @param ignoreEmpty whether empty source values should leave the root field untouched
     * @param toMany whether the root relation field is a to-many relation
     * @param oneToOne whether the root relation field is the main row's own 1:1 sub-record, in which
     *                 case the dotted columns are folded into a nested value object instead of being
     *                 used as a business key to look an existing row up
     * @param relationFilters the root field's own {@code MetaField.filters}, ANDed into the lookup so
     *                        the business key is resolved within the domain the field declares. A
     *                        field pointing at {@code TenantOptionItem} names its option set that way
     *                        ({@code ["optionSetCode", "=", "OrganizationType"]}), and {@code itemCode}
     *                        is only unique inside one set — without it a lookup can match another
     *                        set's row, or match several and fail the import as a duplicate key
     */
    public record LookupGroup(String rootField, String relatedModel, List<String> lookupFields,
                              List<String> dottedPaths, boolean ignoreEmpty, boolean toMany,
                              boolean oneToOne, Filters relationFilters,
                              List<NestedLookup> nestedLookups) {

        /** The shape every group had before nested lookups existed; most still have no nested part. */
        public LookupGroup(String rootField, String relatedModel, List<String> lookupFields,
                           List<String> dottedPaths, boolean ignoreEmpty, boolean toMany,
                           boolean oneToOne, Filters relationFilters) {
            this(rootField, relatedModel, lookupFields, dottedPaths, ignoreEmpty, toMany, oneToOne,
                    relationFilters, List.of());
        }
    }

    /**
     * One column of a one-to-one group that names a relation <b>inside</b> the sub-record by a
     * business field — {@code employeeProfileId.idType.name}.
     *
     * <p>Exists because the sub-record's own relations were unreachable by anything readable. The
     * two-segment form stops at the relation and the cell must hold the foreign key itself — fine
     * while the target's id is a code ({@code SG_NRIC}), useless when it is a generated number, which
     * is what every column reaching the time profile or the bank account was stuck with. The third
     * segment names the field to look the target up by instead, and the resolved id is what lands in
     * the nested map — the write pipeline sees exactly the shape it always has.
     *
     * @param nestedField the relation field on the sub-record, e.g. {@code idType}
     * @param relatedModel the model that relation points at, e.g. {@code IdType}
     * @param lookupField the business field on it the cell carries, e.g. {@code name}
     * @param dottedPath the original template column, e.g. {@code employeeProfileId.idType.name}
     * @param relationFilters the nested field's own {@code MetaField.filters}, same role as on the group
     */
    public record NestedLookup(String nestedField, String relatedModel, String lookupField,
                               String dottedPath, Filters relationFilters) {}

    /**
     * Detect, validate and return the lookup groups from the import field list.
     *
     * @param modelName the target model name
     * @param importFields the list of import field DTOs
     * @return a list of LookupGroup describing the relation lookups
     */
    public List<LookupGroup> detectLookupGroups(String modelName, List<ImportFieldDTO> importFields) {
        // Collect all fieldNames
        Set<String> directFields = new HashSet<>();
        // rootField -> list of ImportFieldDTOs with dotted paths
        Map<String, List<ImportFieldDTO>> rootToDottedFields = new LinkedHashMap<>();

        for (ImportFieldDTO field : importFields) {
            String fieldName = field.getFieldName();
            if (!fieldName.contains(".")) {
                directFields.add(fieldName);
                continue;
            }
            // Has dot: validate relation lookup
            String[] parts = fieldName.split("\\.");
            if (parts.length > 3) {
                throw new IllegalArgumentException(
                        "Import field `{0}` has more than two levels of cascade. " +
                        "Supported forms are deptId.code, and employeeProfileId.idType.name where " +
                        "the first hop is the row's own one-to-one sub-record.",
                        fieldName);
            }

            String rootField = parts[0];
            // Validate root field is ManyToOne/OneToOne
            if (!ModelManager.existField(modelName, rootField)) {
                throw new IllegalArgumentException(
                        "Import field `{0}`: root field `{1}` does not exist in model `{2}`.",
                        fieldName, rootField, modelName);
            }
            MetaField rootMetaField = ModelManager.getModelField(modelName, rootField);
            if (!FieldType.RELATED_TYPES.contains(rootMetaField.getFieldType())) {
                throw new IllegalArgumentException(
                        "Import field `{0}`: root field `{1}` must be a relation field, but is `{2}`.",
                        fieldName, rootField, rootMetaField.getFieldType());
            }
            if (parts.length == 3) {
                validateNestedLookupPath(modelName, fieldName, rootMetaField, parts);
            }
            rootToDottedFields.computeIfAbsent(rootField, ignored -> new ArrayList<>()).add(field);
        }

        for (String rootField : rootToDottedFields.keySet()) {
            if (directFields.contains(rootField)) {
                List<String> paths = rootToDottedFields.get(rootField).stream()
                        .map(ImportFieldDTO::getFieldName).toList();
                throw new IllegalArgumentException(
                        "Import field `{0}` and `{1}` cannot coexist. " +
                                "Either import the relation value directly or use relation lookup, not both.",
                        rootField, paths);
            }
        }

        // Build lookup groups
        List<LookupGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<ImportFieldDTO>> entry : rootToDottedFields.entrySet()) {
            String rootField = entry.getKey();
            List<ImportFieldDTO> fieldDTOs = entry.getValue();
            MetaField rootMetaField = ModelManager.getModelField(modelName, rootField);
            String relatedModel = rootMetaField.getRelatedModel();
            List<String> dottedPaths = fieldDTOs.stream().map(ImportFieldDTO::getFieldName).toList();
            Assert.notBlank(relatedModel,
                    "Import field `{0}`: root field `{1}` has no related model configured.",
                    dottedPaths, rootField);
            // Extract the lookup field names in the related model (e.g. "code" from "deptId.code")
            List<String> lookupFields = dottedPaths.stream()
                    .map(path -> path.substring(rootField.length() + 1))
                    .toList();
            boolean ignoreEmpty = Boolean.TRUE.equals(fieldDTOs.getFirst().getIgnoreEmpty());
            boolean toMany = FieldType.TO_MANY_TYPES.contains(rootMetaField.getFieldType());
            boolean oneToOne = FieldType.ONE_TO_ONE.equals(rootMetaField.getFieldType());
            List<NestedLookup> nestedLookups = new ArrayList<>();
            Set<String> nestedKeys = new HashSet<>();
            for (String path : dottedPaths) {
                String[] segments = path.split("\\.");
                String key = segments[1];
                if (!nestedKeys.add(key)) {
                    // deptId.code twice is caught by column validation upstream; what arrives here is
                    // employeeProfileId.idType next to employeeProfileId.idType.name — two columns
                    // writing the same nested field, one of which would silently win.
                    throw new IllegalArgumentException(
                            "Import fields address `{0}.{1}` more than once; a sub-record field can " +
                                    "be written by only one column.",
                            rootField, key);
                }
                if (segments.length == 3) {
                    MetaField nestedMetaField = ModelManager.getModelField(relatedModel, segments[1]);
                    nestedLookups.add(new NestedLookup(segments[1], nestedMetaField.getRelatedModel(),
                            segments[2], path, Filters.of(nestedMetaField.getFilters())));
                }
            }
            groups.add(new LookupGroup(rootField, relatedModel, lookupFields, dottedPaths, ignoreEmpty,
                    toMany, oneToOne, Filters.of(rootMetaField.getFilters()), List.copyOf(nestedLookups)));
        }
        warnIfPartitionedLookupHasNoCompanyColumn(modelName, importFields, groups);
        return groups;
    }

    /**
     * A template that looks a country-partitioned target up by name without carrying the row's
     * company cannot be resolved per row: the same name may exist in every country the importer works
     * in, and a multi-country importer then fails the row as a duplicate key. Nothing is rejected —
     * the template works for a single-country importer — but the gap is worth a line in the log, and
     * the same check is exposed for a template editor to show.
     */
    void warnIfPartitionedLookupHasNoCompanyColumn(String modelName, List<ImportFieldDTO> importFields,
                                                   List<LookupGroup> groups) {
        List<String> gaps = partitionedLookupsWithoutCompanyColumn(modelName, importFields, groups);
        if (!gaps.isEmpty()) {
            log.warn("Import template for {} looks up country-partitioned values ({}) but carries no company "
                    + "column; rows will resolve within the importer's countries, not their own", modelName, gaps);
        }
    }

    /** The dotted lookup paths onto {@code multiCountry} targets in a template that names no company. */
    public List<String> partitionedLookupsWithoutCompanyColumn(String modelName, List<ImportFieldDTO> importFields,
                                                              List<LookupGroup> groups) {
        if (!ModelManager.existModel(modelName)) {
            return List.of();
        }
        Set<String> companyFields = new HashSet<>();
        for (MetaField field : ModelManager.getModelFields(modelName)) {
            if (FieldType.MANY_TO_ONE.equals(field.getFieldType())
                    && ModelConstant.COMPANY_MODEL.equals(field.getRelatedModel())) {
                companyFields.add(field.getFieldName());
            }
        }
        boolean hasCompanyColumn = importFields.stream()
                .map(ImportFieldDTO::getFieldName)
                .anyMatch(name -> companyFields.contains(name) || companyFields.contains(name.split("\\.")[0]));
        if (hasCompanyColumn) {
            return List.of();
        }
        List<String> gaps = new ArrayList<>();
        for (LookupGroup group : groups) {
            if (!group.oneToOne() && isPartitioned(group.relatedModel())) {
                gaps.addAll(group.dottedPaths());
            }
            for (NestedLookup nested : group.nestedLookups()) {
                if (isPartitioned(nested.relatedModel())) {
                    gaps.add(nested.dottedPath());
                }
            }
        }
        return gaps;
    }

    private static boolean isPartitioned(String modelName) {
        return modelName != null && ModelManager.existModel(modelName) && ModelManager.getModel(modelName).isMultiCountry();
    }

    /**
     * A three-segment path is allowed through exactly one shape, and this rejects every other.
     *
     * <p>The first hop must be the row's own one-to-one sub-record: its columns are folded into a
     * nested value object rather than used as a business key, so there is a place for a resolved id
     * to land. Through a many-to-one the two segments already are the business key of some other row,
     * and a third would change what the column means. The middle field must be a many-to-one — that is
     * the thing being named — and the leaf must be a plain field on its target, because the leaf is
     * what the cell carries and a relation cannot be typed into a cell.
     */
    private void validateNestedLookupPath(String modelName, String fieldName, MetaField rootMetaField,
                                          String[] parts) {
        if (!FieldType.ONE_TO_ONE.equals(rootMetaField.getFieldType())) {
            throw new IllegalArgumentException(
                    "Import field `{0}`: a two-level path is only supported through the row's own " +
                            "one-to-one sub-record, but `{1}` is `{2}`.",
                    fieldName, parts[0], rootMetaField.getFieldType());
        }
        String subRecordModel = rootMetaField.getRelatedModel();
        if (!ModelManager.existField(subRecordModel, parts[1])) {
            throw new IllegalArgumentException(
                    "Import field `{0}`: `{1}` does not exist in model `{2}`.",
                    fieldName, parts[1], subRecordModel);
        }
        MetaField nestedMetaField = ModelManager.getModelField(subRecordModel, parts[1]);
        if (!FieldType.MANY_TO_ONE.equals(nestedMetaField.getFieldType())) {
            throw new IllegalArgumentException(
                    "Import field `{0}`: `{1}.{2}` must be a many-to-one relation to be looked up by " +
                            "`{3}`, but is `{4}`.",
                    fieldName, subRecordModel, parts[1], parts[2], nestedMetaField.getFieldType());
        }
        String targetModel = nestedMetaField.getRelatedModel();
        Assert.notBlank(targetModel,
                "Import field `{0}`: `{1}.{2}` has no related model configured.",
                fieldName, subRecordModel, parts[1]);
        if (!ModelManager.existField(targetModel, parts[2])) {
            throw new IllegalArgumentException(
                    "Import field `{0}`: `{1}` does not exist in model `{2}`.",
                    fieldName, parts[2], targetModel);
        }
        if (FieldType.RELATED_TYPES.contains(ModelManager.getModelField(targetModel, parts[2]).getFieldType())) {
            throw new IllegalArgumentException(
                    "Import field `{0}`: `{1}.{2}` is a relation itself and cannot be the value a " +
                            "cell carries.",
                    fieldName, targetModel, parts[2]);
        }
    }

    /**
     * Resolve all relation lookup fields in the rows: for each lookup group,
     * batch-query the related model, write back the real FK id, and remove the dotted-path temporary columns.
     *
     * @param rows the import data rows
     * @param lookupGroups the lookup groups detected by {@link #detectLookupGroups}
     * @param skipException when false, throw ValidationException on lookup failure instead of marking FAILED_REASON
     */
    public void resolveRows(List<Map<String, Object>> rows, List<LookupGroup> lookupGroups, boolean skipException) {
        resolveRows(null, rows, lookupGroups, skipException);
    }

    /**
     * Same, naming the importing model so that country-partitioned targets can be resolved in each
     * row's own country (the row's company reference is a field of that model). Without the model
     * name every lookup runs unbucketed.
     *
     * <p>Naming the model also lets a row name another row of the same file through a many-to-one onto
     * that model — a manager, a parent department. Such a value matches nothing stored yet, since no row
     * of the file is written before every lookup has run; when it matches a row of the file it is not a
     * failure but a reference to fill once that row exists, and is returned for the write to settle.
     *
     * @return the references to other rows of this file, left unresolved
     */
    public List<InFileReferences.Reference> resolveRows(String modelName, List<Map<String, Object>> rows,
                                                        List<LookupGroup> lookupGroups, boolean skipException) {
        List<InFileReferences.Reference> inFile = new ArrayList<>();
        // A group that resolves the row's company goes first, whatever the template's column order:
        // every country-partitioned lookup after it needs the company id already written back.
        List<LookupGroup> ordered = new ArrayList<>(lookupGroups);
        ordered.sort(Comparator.comparing((LookupGroup g) -> !ModelConstant.COMPANY_MODEL.equals(g.relatedModel())));
        for (LookupGroup group : ordered) {
            if (group.oneToOne()) {
                resolveNestedOneToOneGroup(modelName, rows, group, skipException);
            } else if (group.toMany()) {
                resolveToManyGroup(modelName, rows, group, skipException);
            } else {
                resolveToOneGroup(modelName, rows, group, skipException, inFile);
            }
        }
        return inFile;
    }

    /**
     * Whether a group names a row of the importing model itself by a business key — {@code
     * piEmployeeId.code} on an employee, {@code parentId.code} on a department. Only a plain many-to-one
     * does: a to-many cell names several rows and a one-to-one is the row's own sub-record.
     */
    static boolean isSelfReference(String modelName, LookupGroup group) {
        return modelName != null && modelName.equals(group.relatedModel())
                && !group.toMany() && !group.oneToOne() && group.nestedLookups().isEmpty();
    }

    /** The rows of this file by the values a self-reference names them with; the first of a repeated key wins. */
    private static Map<List<Object>, Map<String, Object>> rowsByOwnKey(List<Map<String, Object>> rows,
                                                                      List<String> fields) {
        Map<List<Object>, Map<String, Object>> byKey = new HashMap<>();
        for (Map<String, Object> row : rows) {
            List<Object> key = InFileReferences.ownKey(row, fields);
            if (key != null) {
                byKey.putIfAbsent(key, row);
            }
        }
        return byKey;
    }

    private static List<Object> normalizedKey(List<Object> keyValues) {
        List<Object> key = new ArrayList<>(keyValues.size());
        for (Object value : keyValues) {
            Object normalized = InFileReferences.normalize(value);
            if (normalized == null) {
                return null;
            }
            key.add(normalized);
        }
        return key;
    }

    /**
     * The country a row's values belong to: that of the company the row names, or {@code null}.
     *
     * <p>The company reference is the importing model's field onto the company model — found by
     * target, not by name, because the HR app calls it {@code legalEntityId} on an employee and
     * {@code companyId} on a department. Only a value already written back as an id counts: a dotted
     * lookup column still holding a name has not been resolved yet, which is why company groups are
     * resolved first. Rows with several company fields take the first that carries a value.
     */
    String countryOfRow(String modelName, Map<String, Object> row) {
        if (companyCountryResolver == null || !ModelManager.existModel(modelName)) {
            return null;
        }
        for (MetaField field : ModelManager.getModelFields(modelName)) {
            if (!FieldType.MANY_TO_ONE.equals(field.getFieldType())
                    || !ModelConstant.COMPANY_MODEL.equals(field.getRelatedModel())) {
                continue;
            }
            Object value = row.get(field.getFieldName());
            Long companyId = value instanceof Number n ? n.longValue()
                    : value instanceof String text && StringUtils.isNumeric(text.trim()) ? Long.valueOf(text.trim())
                    : null;
            if (companyId != null) {
                return companyCountryResolver.resolveCountry(companyId);
            }
        }
        return null;
    }

    /**
     * {@code getIdsByBusinessKeys} per country bucket for a country-partitioned target; one call, as
     * before, for anything else. The result is keyed by (country, business key) so the write-back can
     * pick a row's own bucket.
     */
    private Bucketed lookupByCountry(String modelName, String relatedModel,
                                     List<String> lookupFields, Filters relationFilters,
                                     List<Map<String, Object>> rows,
                                     java.util.function.Function<Map<String, Object>, Collection<List<Object>>> keysOf) {
        boolean partitioned = ModelManager.existModel(relatedModel) && ModelManager.getModel(relatedModel).isMultiCountry();
        Map<String, Set<List<Object>>> keysByCountry = new LinkedHashMap<>();
        // Identity-keyed: rows are mutable maps whose hash changes as ids are written back.
        Map<Map<String, Object>, String> countryByRow = new IdentityHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.containsKey(FileConstant.FAILED_REASON)) {
                continue;
            }
            Collection<List<Object>> keys = keysOf.apply(row);
            if (keys == null || keys.isEmpty()) {
                continue;
            }
            String country = partitioned ? countryOfRow(modelName, row) : null;
            countryByRow.put(row, country);
            keysByCountry.computeIfAbsent(country, ignored -> new LinkedHashSet<>()).addAll(keys);
        }
        Map<String, Map<List<Object>, ?>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Set<List<Object>>> bucket : keysByCountry.entrySet()) {
            String country = bucket.getKey();
            Filters scope = country == null
                    ? relationFilters
                    : Filters.and(relationFilters, Filters.of(ModelConstant.COUNTRY_FIELD, Operator.EQUAL, country));
            resolved.put(country, modelService.getIdsByBusinessKeys(relatedModel, lookupFields, bucket.getValue(), scope));
        }
        return new Bucketed(resolved, countryByRow);
    }

    /** The per-country lookup results plus, per row, which bucket it was resolved in. */
    private record Bucketed(Map<String, Map<List<Object>, ?>> byCountry,
                            Map<Map<String, Object>, String> countryByRow) {
        Object idFor(Map<String, Object> row, List<Object> key) {
            return byCountry.getOrDefault(countryByRow.get(row), Map.of()).get(key);
        }
    }

    /**
     * Fold one OneToOne group into a nested value object on the root field.
     *
     * <p>A OneToOne root is the main row's <b>own</b> 1:1 sub-record (e.g. {@code Employee
     * .employeeProfileId}), not a reference to some pre-existing row, so its dotted columns carry the
     * sub-record's content rather than a business key to search by. They are collected into a nested
     * map, which the ORM write pipeline creates or updates inline — see
     * {@code XToOneGroupProcessor#processNestedOneToOneRows}. Looking such a group up instead would be
     * meaningless (no row matches "every attribute equal") and blows up on the first blank cell, since
     * a business key may not contain nulls.
     *
     * <p>Blank cells are omitted from the map, so "blank means keep the existing value" holds on
     * update. An all-blank group still yields an <i>empty</i> map rather than nothing: the sub-record
     * belongs to the main row, so a create must still produce one (the owning FK is typically
     * required) and an update simply relinks the sub-row already there without touching a field.
     */
    private void resolveNestedOneToOneGroup(String modelName, List<Map<String, Object>> rows, LookupGroup group,
                                            boolean skipException) {
        // Business values first, ids after: each nested lookup is one query across every row, the
        // same bargain resolveToOneGroup strikes — per-row queries would turn a sheet into N calls.
        Map<String, Bucketed> resolvedByPath = resolveNestedLookupValues(modelName, rows, group);
        for (Map<String, Object> row : rows) {
            if (!row.containsKey(FileConstant.FAILED_REASON)) {
                Map<String, Object> nested = new LinkedHashMap<>();
                for (int i = 0; i < group.dottedPaths().size(); i++) {
                    String lookupField = group.lookupFields().get(i);
                    if (lookupField.contains(".")) {
                        // A nested relation column; its resolved id is written below.
                        continue;
                    }
                    Object value = row.get(group.dottedPaths().get(i));
                    if (value != null && (!(value instanceof String text) || !text.isBlank())) {
                        nested.put(lookupField, value);
                    }
                }
                boolean failed = false;
                for (NestedLookup nestedLookup : group.nestedLookups()) {
                    Object raw = row.get(nestedLookup.dottedPath());
                    if (raw == null || (raw instanceof String text && text.isBlank())) {
                        // Blank keeps the existing value, exactly like every other nested column.
                        continue;
                    }
                    Object resolvedId = resolvedByPath.get(nestedLookup.dottedPath()).idFor(row, List.of(raw));
                    if (resolvedId == null) {
                        markFailure(row, buildNotFoundMessage(subjectOf(group.relatedModel(), nestedLookup.nestedField(),
                                nestedLookup.relatedModel()),
                                List.of(nestedLookup.lookupField()), List.of(raw)), skipException);
                        failed = true;
                        break;
                    }
                    nested.put(nestedLookup.nestedField(), resolvedId);
                }
                if (!failed) {
                    row.put(group.rootField(), nested);
                }
            }
            removeDottedPaths(row, group);
        }
    }

    /**
     * One batched lookup per nested relation column, keyed by the column's dotted path.
     *
     * <p>The query goes through {@code getIdsByBusinessKeys} like every two-segment lookup, which is
     * where the two guarantees come from: the value must match exactly one row — several matches fail
     * the call loudly rather than picking one — and the search runs under the caller's access scopes,
     * so a multi-country target is narrowed to the requesting company's country. {@code Passport}
     * names a different row in every country that has one; without the narrowing this lookup would be
     * wrong the day a second country seeds it.
     */
    private Map<String, Bucketed> resolveNestedLookupValues(String modelName, List<Map<String, Object>> rows,
                                                            LookupGroup group) {
        Map<String, Bucketed> resolvedByPath = new LinkedHashMap<>();
        for (NestedLookup nestedLookup : group.nestedLookups()) {
            resolvedByPath.put(nestedLookup.dottedPath(), lookupByCountry(modelName, nestedLookup.relatedModel(),
                    List.of(nestedLookup.lookupField()), nestedLookup.relationFilters(), rows, row -> {
                        Object raw = row.get(nestedLookup.dottedPath());
                        boolean present = raw != null && (!(raw instanceof String text) || !text.isBlank());
                        return present ? List.of(List.of(raw)) : List.of();
                    }));
        }
        return resolvedByPath;
    }

    /**
     * Resolve one lookup group across all rows.
     */
    private void resolveToOneGroup(String modelName, List<Map<String, Object>> rows, LookupGroup group,
                                   boolean skipException, List<InFileReferences.Reference> inFile) {
        Set<List<Object>> distinctKeys = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (row.containsKey(FileConstant.FAILED_REASON)) {
                continue;
            }
            List<Object> keyValues = extractKeyValues(row, group);
            if (keyValues != null) {
                distinctKeys.add(keyValues);
            }
        }

        if (distinctKeys.isEmpty()) {
            // All values are empty/null — handle empty rootField and clean up dotted paths
            handleEmptyAndCleanup(rows, group);
            return;
        }

        // Step 2: Batch query related model to get businessKey -> id mapping, per row country when the
        // target is partitioned by country (see the class comment).
        Bucketed lookedUp = lookupByCountry(modelName, group.relatedModel(),
                group.lookupFields(), group.relationFilters(), rows, row -> {
                    List<Object> keys = extractKeyValues(row, group);
                    return keys == null ? List.of() : List.of(keys);
                });

        // Rows of this file the group may name, when it names rows of the importing model at all.
        boolean selfReference = isSelfReference(modelName, group);
        Map<List<Object>, Map<String, Object>> fileRows = selfReference
                ? rowsByOwnKey(rows, group.lookupFields()) : Map.of();
        String subject = subjectOf(modelName, group.rootField(), group.relatedModel());

        // Step 3: Write back the FK id and remove dotted-path columns
        for (Map<String, Object> row : rows) {
            if (row.containsKey(FileConstant.FAILED_REASON)) {
                removeDottedPaths(row, group);
                continue;
            }
            List<Object> keyValues = extractKeyValues(row, group);
            if (keyValues == null) {
                handleEmptyRootField(row, group);
                removeDottedPaths(row, group);
                continue;
            }
            Object resolvedId = lookedUp.idFor(row, keyValues);
            if (resolvedId != null) {
                row.put(group.rootField(), resolvedId);
            } else {
                List<Object> key = selfReference ? normalizedKey(keyValues) : null;
                Map<String, Object> target = key == null ? null : fileRows.get(key);
                if (target == row) {
                    markFailure(row, subject + " cannot be the row itself.", skipException);
                } else if (target != null) {
                    // Left unset: the row it names gets its id when the file is written, and the write
                    // fills it in before this row goes in.
                    inFile.add(new InFileReferences.Reference(row, group.rootField(),
                            subject, group.lookupFields(), key, target));
                } else {
                    markFailure(row, buildNotFoundMessage(subject, group, keyValues), skipException);
                }
            }
            removeDottedPaths(row, group);
        }
    }

    /**
     * Resolves one to-many lookup group.
     *
     * <p>Example: {@code roleIds.code -> roleIds} where the source cell may contain
     * comma-separated values such as {@code ADMIN,USER}.</p>
     */
    private void resolveToManyGroup(String modelName, List<Map<String, Object>> rows, LookupGroup group,
                                    boolean skipException) {
        Set<List<Object>> distinctKeys = new LinkedHashSet<>();
        Map<Map<String, Object>, List<List<Object>>> rowResolvedKeys = new IdentityHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row.containsKey(FileConstant.FAILED_REASON)) {
                continue;
            }
            List<List<Object>> keyGroups = extractToManyKeyValues(row, group);
            if (keyGroups != null) {
                rowResolvedKeys.put(row, keyGroups);
                distinctKeys.addAll(keyGroups);
            }
        }

        if (distinctKeys.isEmpty()) {
            handleEmptyAndCleanup(rows, group);
            return;
        }

        Map<List<Object>, ?> keyToIdMap = modelService.getIdsByBusinessKeys(
                group.relatedModel(), group.lookupFields(), distinctKeys, group.relationFilters());

        for (Map<String, Object> row : rows) {
            if (row.containsKey(FileConstant.FAILED_REASON)) {
                removeDottedPaths(row, group);
                continue;
            }
            List<List<Object>> keyGroups = rowResolvedKeys.get(row);
            if (keyGroups == null) {
                handleEmptyRootField(row, group);
                removeDottedPaths(row, group);
                continue;
            }
            List<Object> resolvedIds = new ArrayList<>();
            List<List<Object>> missingKeys = new ArrayList<>();
            for (List<Object> keyValues : keyGroups) {
                Object resolvedId = keyToIdMap.get(keyValues);
                if (resolvedId == null) {
                    missingKeys.add(keyValues);
                } else {
                    resolvedIds.add(resolvedId);
                }
            }
            if (!missingKeys.isEmpty()) {
                String subject = subjectOf(modelName, group.rootField(), group.relatedModel());
                String message = missingKeys.stream().map(keys -> buildNotFoundMessage(subject, group, keys))
                        .collect(Collectors.joining("; "));
                markFailure(row, message, skipException);
            } else {
                row.put(group.rootField(), resolvedIds);
            }
            removeDottedPaths(row, group);
        }
    }

    /**
     * Handles a lookup failure according to the import mode.
     *
     * <ul>
     *   <li>fail-fast mode: throws {@link ValidationException}</li>
     *   <li>skip mode: appends the message to {@link FileConstant#FAILED_REASON}</li>
     * </ul>
     */
    private void markFailure(Map<String, Object> row, String message, boolean skipException) {
        if (!skipException) {
            throw new ValidationException(message);
        }
        String failedReason = row.containsKey(FileConstant.FAILED_REASON)
                ? row.get(FileConstant.FAILED_REASON) + "; " : "";
        row.put(FileConstant.FAILED_REASON, failedReason + message);
    }

    /**
     * Applies empty-value semantics for the resolved root relation field.
     *
     * <ul>
     *   <li>{@code ignoreEmpty=true}: do not write the root field</li>
     *   <li>to-one + {@code ignoreEmpty=false}: write {@code null}</li>
     *   <li>to-many + {@code ignoreEmpty=false}: write an empty list</li>
     * </ul>
     */
    private void handleEmptyRootField(Map<String, Object> row, LookupGroup group) {
        if (!group.ignoreEmpty()) {
            row.put(group.rootField(), group.toMany() ? Collections.emptyList() : null);
        }
    }

    /**
     * Fast path when all values in a lookup group are empty.
     */
    private void handleEmptyAndCleanup(List<Map<String, Object>> rows, LookupGroup group) {
        for (Map<String, Object> row : rows) {
            if (!row.containsKey(FileConstant.FAILED_REASON)) {
                handleEmptyRootField(row, group);
            }
            removeDottedPaths(row, group);
        }
    }

    /**
     * Extracts one business-key tuple from a row for a to-one lookup group.
     *
     * @return the key tuple, or {@code null} when all source fields are empty
     */
    private List<Object> extractKeyValues(Map<String, Object> row, LookupGroup group) {
        List<Object> values = new ArrayList<>(group.dottedPaths().size());
        boolean allEmpty = true;
        for (String dottedPath : group.dottedPaths()) {
            Object val = row.get(dottedPath);
            if (val instanceof String text) {
                // Spreadsheet cells carry stray spaces constantly — pasted, typed, copied out of a
                // UI. Matched raw, " E1000100" finds nothing and the row is rejected with a message
                // that prints the value back WITH the space in it: invisible in Excel, in a terminal
                // and in a browser alike, so the reader sees the code they typed and the code in the
                // list and cannot tell them apart. The to-many path in this class has trimmed all
                // along, which is what made the difference so hard to see.
                val = text.trim();
            }
            if (val != null && (!(val instanceof String s) || !s.isBlank())) {
                allEmpty = false;
            }
            values.add(val);
        }
        return allEmpty ? null : values;
    }

    /**
     * Extracts multiple business-key tuples from a row for a to-many lookup group.
     *
     * <p>For a single lookup field, a cell like {@code ADMIN,USER} becomes
     * {@code [["ADMIN"], ["USER"]]}.</p>
     *
     * @return a list of key tuples, or {@code null} when all source fields are empty
     */
    private List<List<Object>> extractToManyKeyValues(Map<String, Object> row, LookupGroup group) {
        List<List<String>> splitColumns = new ArrayList<>(group.dottedPaths().size());
        boolean allEmpty = true;
        int expectedSize = -1;
        for (String dottedPath : group.dottedPaths()) {
            Object rawValue = row.get(dottedPath);
            List<String> values = splitToManyCellValues(rawValue);
            if (!values.isEmpty()) {
                allEmpty = false;
            }
            if (expectedSize == -1) {
                expectedSize = values.size();
            } else if (expectedSize != values.size()) {
                throw new ValidationException(
                        "The relation lookup field `{0}` expects the same number of items in columns {1}, but got {2} and {3}.",
                        group.rootField(), group.dottedPaths(), expectedSize, values.size());
            }
            splitColumns.add(values);
        }
        if (allEmpty) {
            return null;
        }
        List<List<Object>> keyGroups = new ArrayList<>(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            List<Object> keyValues = new ArrayList<>(splitColumns.size());
            boolean allItemEmpty = true;
            for (List<String> columnValues : splitColumns) {
                String value = columnValues.get(i);
                if (StringUtils.isNotBlank(value)) {
                    allItemEmpty = false;
                }
                keyValues.add(value);
            }
            if (allItemEmpty) {
                throw new ValidationException(
                        "The relation lookup field `{0}` contains an empty item at position {1} in columns {2}.",
                        group.rootField(), i + 1, group.dottedPaths());
            }
            keyGroups.add(keyValues);
        }
        return keyGroups;
    }

    /**
     * Splits a to-many source cell into individual business-key values.
     */
    private List<String> splitToManyCellValues(Object rawValue) {
        if (rawValue == null) {
            return Collections.emptyList();
        }
        if (rawValue instanceof String str) {
            if (str.isBlank()) {
                return Collections.emptyList();
            }
            return Arrays.stream(str.split(","))
                    .map(String::trim)
                    .toList();
        }
        if (rawValue instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                return Collections.emptyList();
            }
            return collection.stream().map(value -> value == null ? "" : value.toString().trim()).toList();
        }
        return List.of(rawValue.toString().trim());
    }

    /**
     * Removes temporary dotted-path fields after resolution.
     */
    private void removeDottedPaths(Map<String, Object> row, LookupGroup group) {
        for (String dottedPath : group.dottedPaths()) {
            row.remove(dottedPath);
        }
    }

    /**
     * Why a lookup found nothing.
     *
     * <p>With one column the message speaks for itself: that value matches no row. With several it
     * does not. They are ANDed, so a row can fail while every value in it is perfectly real — they
     * simply do not belong to one record. A template that asks for both a code and a name is asking
     * for exactly that check: the name is there to catch a mistyped code, and "cannot find" is what
     * catching it looks like. Left unexplained, the reader sees two values they know exist and
     * concludes the import is broken rather than that it just did its job.
     */
    private String buildNotFoundMessage(String subject, LookupGroup group, List<Object> keyValues) {
        return buildNotFoundMessage(subject, group.lookupFields(), keyValues);
    }

    /**
     * "IPA Position not found: Bar Bender" — named the way the sheet's reader knows the column, by the
     * relation's label, not by the model behind it ({@code IpaPosition}) and the field it was matched
     * on, which they never see. Several columns name the fields, since the reader needs to know which
     * values were taken together.
     */
    private String buildNotFoundMessage(String subject, List<String> lookupFields, List<Object> keyValues) {
        StringBuilder sb = new StringBuilder(subject).append(" not found: ");
        if (lookupFields.size() == 1) {
            return sb.append(keyValues.getFirst()).toString();
        }
        for (int i = 0; i < lookupFields.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(lookupFields.get(i)).append("=").append(keyValues.get(i));
        }
        sb.append(" — these must all describe the same ").append(subject)
                .append("; no one record matches them together.");
        return sb.toString();
    }

    /**
     * What a missed lookup is called in its message: the relation's own label on {@code modelName}, or —
     * when the field is not known there — the related model's label, and its name as a last resort.
     */
    private static String subjectOf(String modelName, String rootField, String relatedModel) {
        MetaField field = modelName == null ? null : ModelManager.getModelFieldOrNull(modelName, rootField);
        if (field != null && StringUtils.isNotBlank(field.getLabel())) {
            return field.getLabel();
        }
        if (StringUtils.isNotBlank(relatedModel) && ModelManager.existModel(relatedModel)) {
            String label = ModelManager.getModel(relatedModel).getLabel();
            if (StringUtils.isNotBlank(label)) {
                return label;
            }
        }
        return relatedModel;
    }
}
