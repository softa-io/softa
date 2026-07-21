package io.softa.starter.metadata.scanner.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.metadata.ddl.SqlReservedWords;
import io.softa.starter.metadata.ddl.spi.BuiltinDdlMetadataResolver;
import io.softa.starter.metadata.ddl.spi.FieldDdlDefault;
import io.softa.starter.metadata.entity.*;
import io.softa.starter.metadata.scanner.annotation.inference.JsonValueResolver;
import io.softa.starter.metadata.scanner.annotation.inference.ReflectionTypes;
import io.softa.starter.metadata.scanner.annotation.inference.TypeInference;

/**
 * Parses {@code @Model} / {@code @Field} / {@code @OptionSet} / {@code @OptionItem}
 * annotations into Sys* entities.
 *
 * <p>Output entities are partially populated: annotation-derived fields only.
 * Surrogate / FK columns ({@code appId} / {@code modelId} / FK relations /
 * {@code modelFields}) are DB-side concerns and left {@code null} for downstream
 * layers (DiffEngine, persistence) to handle. The primary-key {@code id} is
 * always emitted as a field (see {@link #buildIdField}).
 *
 * <p>Naming rules:
 * <ul>
 *   <li>{@code modelName} = class simple name (no override)</li>
 *   <li>{@code fieldName} = Java field name (no override)</li>
 *   <li>{@code optionSetCode} = enum class simple name (no override)</li>
 *   <li>{@code itemCode} = value of {@code @JsonValue}-annotated field/method
 *       on the enum (fallback to {@code enumConstant.name()})</li>
 * </ul>
 *
 * <p>Inheritance: fields are collected by walking the superclass chain, so
 * {@code @Field}-annotated structural fields declared once on
 * {@code AuditableModel} / {@code TimelineModel} (audit, {@code sliceId},
 * effective dates) are emitted for every model. A subclass declaration shadows
 * a superclass field of the same name.
 *
 * <p>Pure POJO — no Spring dependency.
 */
public final class AnnotationParser {

    /**
     * Default VARCHAR length for a string primary key when {@code @Field} does
     * not specify one: CosID Radix36 ids are &le;13 chars (Radix62 &le;11); 24
     * leaves headroom and stays index-friendly.
     */
    private static final int STRING_ID_LENGTH = 24;

    /**
     * Max characters for a {@code description} on {@code @Model} / {@code @Field} /
     * {@code @OptionSet} / {@code @OptionItem}, read off {@code SysField.description}'s
     * own {@code @Field(length)} — the width every sys_* / design_* catalog table
     * (and their *Trans twins) declares for its description column.
     */
    private static final int DESCRIPTION_MAX_LENGTH = fieldLength(SysField.class, "description");

    /** Max lengths for the {@code @Index}-derived {@code sys_model_index} columns. */
    private static final int INDEX_NAME_MAX = fieldLength(SysModelIndex.class, "indexName");
    private static final int MESSAGE_MAX = fieldLength(SysModelIndex.class, "message");

    /**
     * A catalog entity's declared column width, read reflectively from its own
     * {@code @Field.length()} so a later widening cannot orphan a stale literal
     * here (single source of truth).
     */
    private static int fieldLength(Class<?> entity, String fieldName) {
        try {
            return ormField(entity.getDeclaredField(fieldName)).length();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(entity.getSimpleName() + "." + fieldName + " not found", e);
        }
    }

    /**
     * Parse the supplied classes and enums into Sys* entities.
     *
     * @param modelClasses    classes annotated with {@link Model}
     * @param optionSetEnums  enums annotated with {@link OptionSet}
     * @return populated {@link AnnotationScanResult}
     */
    public AnnotationScanResult parse(
            Collection<Class<?>> modelClasses,
            Collection<Class<?>> optionSetEnums) {

        List<SysModel> models = new ArrayList<>();
        List<SysField> fields = new ArrayList<>();
        List<SysOptionSet> optionSets = new ArrayList<>();
        List<SysOptionItem> optionItems = new ArrayList<>();
        List<SysModelIndex> modelIndexes = new ArrayList<>();

        // catalog business keys — uniqueness enforced by guardUniqueSimpleName
        Map<String, Class<?>> byModelName = new LinkedHashMap<>();
        Map<String, Class<?>> byOptionSetCode = new LinkedHashMap<>();

        for (Class<?> clazz : modelClasses) {
            Model model = clazz.getAnnotation(Model.class);
            if (model == null) {
                throw new IllegalArgumentException(
                        "Class " + clazz.getName() + " is not annotated with @Model");
            }
            guardUniqueSimpleName(byModelName, clazz, "modelName");
            SysModel sysModel = parseModel(clazz, model);
            models.add(sysModel);
            List<SysField> classFields = parseFields(clazz);
            fields.addAll(classFields);
            modelIndexes.addAll(parseIndexes(clazz, sysModel.getTableName(), classFields));
            validateModelFieldRefs(clazz, model, classFields);
        }

        for (Class<?> enumClass : optionSetEnums) {
            if (!enumClass.isEnum()) {
                throw new IllegalArgumentException(
                        "Class " + enumClass.getName() + " is not an enum");
            }
            OptionSet optionSet = enumClass.getAnnotation(OptionSet.class);
            if (optionSet == null) {
                throw new IllegalArgumentException(
                        "Enum " + enumClass.getName() + " is not annotated with @OptionSet");
            }
            guardUniqueSimpleName(byOptionSetCode, enumClass, "optionSetCode");
            optionSets.add(parseOptionSet(enumClass, optionSet));
            optionItems.addAll(parseOptionItems(enumClass));
        }

        RenameDeclarations renames = collectRenames(modelClasses);
        return new AnnotationScanResult(models, fields, optionSets, optionItems, modelIndexes, renames);
    }

    /**
     * Guard the catalog business key (the SIMPLE class name): two same-named classes
     * in different packages would silently merge into one key downstream (diff maps
     * are keyed, last wins), before anything is written and beyond later detection —
     * fail here, naming both declarations.
     */
    private static void guardUniqueSimpleName(
            Map<String, Class<?>> byKey, Class<?> clazz, String keyKind) {
        Class<?> prev = byKey.putIfAbsent(clazz.getSimpleName(), clazz);
        if (prev != null) {
            throw new IllegalStateException("Duplicate " + keyKind + " '" + clazz.getSimpleName()
                    + "': " + prev.getName() + " and " + clazz.getName()
                    + " — " + keyKind + " is the catalog business key and must be globally unique;"
                    + " rename one " + (clazz.isEnum() ? "enum" : "class") + ".");
        }
    }

    // ------------------------------------------------------------ renamedFrom

    /**
     * Collect the single-step {@code renamedFrom} declarations (the {@code @Model}/{@code @Field}
     * attribute that superseded the standalone {@code @RenamedFrom} annotation) off the model classes and
     * their {@code @Field} fields, into {@link RenameDeclarations} keyed to match {@code DiffEngine}'s
     * business keys. Parse-time silent-collapse guards: a declared prior name must not still be a live
     * model/field, and no two siblings may claim the same prior name. Other resolution (both-exist /
     * already-applied) is a diff-time concern.
     */
    private RenameDeclarations collectRenames(Collection<Class<?>> modelClasses) {
        Map<String, String> modelOldNames = new LinkedHashMap<>();
        Map<String, String> fieldOldNames = new LinkedHashMap<>();

        Set<String> currentModelNames = new HashSet<>();
        for (Class<?> clazz : modelClasses) {
            currentModelNames.add(clazz.getSimpleName());
        }

        // ---- model renames ----
        Map<String, String> oldModelClaimedBy = new HashMap<>();
        for (Class<?> clazz : modelClasses) {
            String modelName = clazz.getSimpleName();
            Model modelAnno = clazz.getAnnotation(Model.class);
            String old = renamedFromOf(modelAnno == null ? null : modelAnno.renamedFrom());
            if (old == null) {
                continue;
            }
            claimRename("model", modelName, old, currentModelNames, oldModelClaimedBy);
            modelOldNames.put(modelName, old);
        }

        // ---- field renames (per model) ----
        for (Class<?> clazz : modelClasses) {
            String modelName = clazz.getSimpleName();
            Set<String> currentFieldNames = metadataFieldNames(clazz);
            Map<String, String> oldFieldClaimedBy = new HashMap<>();
            for (Field jf : metadataFields(clazz)) {
                String old = renamedFromOf(ormField(jf).renamedFrom());
                if (old == null) {
                    continue;
                }
                String owner = modelName + "." + jf.getName();
                claimRename("field", owner, old, currentFieldNames, oldFieldClaimedBy);
                fieldOldNames.put(owner, old);
            }
        }
        return new RenameDeclarations(modelOldNames, fieldOldNames);
    }

    /**
     * Register a single-step rename claim: the prior name must not still be live,
     * and no two owners may claim one prior name — either would silently collapse
     * business keys in the diff.
     */
    private static void claimRename(
            String what, String owner, String old,
            Set<String> liveNames, Map<String, String> claimedBy) {
        if (liveNames.contains(old)) {
            throw new IllegalStateException("renamedFrom on " + what + " " + owner
                    + " lists prior name '" + old + "', which is still a live " + what
                    + " — a rename cannot claim a name that still exists.");
        }
        String prev = claimedBy.putIfAbsent(old, owner);
        if (prev != null) {
            throw new IllegalStateException(StringUtils.capitalize(what) + "s " + prev + " and " + owner
                    + " both declare renamedFrom=\"" + old + "\"; a prior name can be claimed once.");
        }
    }

    /** The set of {@code @Field}-annotated field names on a model (chain-walked, leaf-wins). */
    private Set<String> metadataFieldNames(Class<?> clazz) {
        Set<String> names = new HashSet<>();
        for (Field jf : metadataFields(clazz)) {
            names.add(jf.getName());
        }
        return names;
    }

    /**
     * The {@code @Field}-annotated declared fields of a model, in declaration order,
     * with the superclass chain walked so structural fields declared once on
     * {@code AuditableModel} / {@code TimelineModel} are included. Static / synthetic
     * (Lombok) artifacts are skipped and a subclass declaration shadows a superclass
     * field of the same name (leaf wins). The single source of the model's metadata
     * field surface, shared by {@link #parseFields}, {@link #metadataFieldNames} and
     * the rename collector.
     */
    private static List<Field> metadataFields(Class<?> clazz) {
        List<Field> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field jf : c.getDeclaredFields()) {
                if (Modifier.isStatic(jf.getModifiers()) || jf.isSynthetic()) {
                    continue;
                }
                if (!seen.add(jf.getName())) {
                    continue; // shadowed by a subclass declaration (leaf wins)
                }
                if (ormField(jf) != null) {
                    out.add(jf);
                }
            }
        }
        return out;
    }

    /**
     * The single {@code renamedFrom} attribute (on {@code @Model}/{@code @Field} — single-step,
     * no chain): the prior name, or {@code null} when blank/absent (no rename).
     */
    private static String renamedFromOf(String attr) {
        return StringUtils.isNotBlank(attr) ? attr : null;
    }

    // ---------------------------------------------------------------- @Model

    private SysModel parseModel(Class<?> clazz, Model anno) {
        String modelName = clazz.getSimpleName();

        SysModel m = new SysModel();
        m.setModelName(modelName);
        m.setLabel(labelOf(anno.label(), modelName));
        m.setTableName(StringUtils.isBlank(anno.tableName())
                ? StringTools.toUnderscoreCase(modelName)
                : anno.tableName());
        validateSqlIdentifier("Table name", m.getTableName(), "of model " + modelName,
                "Declare a different @Model(tableName = ...), e.g. \"biz_"
                        + m.getTableName() + "\".");
        m.setDescription(checkedDescription(anno.description(), "model " + modelName));
        m.setDisplayName(toList(anno.displayName()));
        m.setSearchName(toList(anno.searchName()));
        // @Model.defaultOrder() is String[] e.g. {"createdTime:desc"}.
        // SysModel.defaultOrder is Orders, parsed from the same string format.
        String[] orderEntries = anno.defaultOrder();
        if (orderEntries.length > 0) {
            // Convert "field:dir" annotation form to "field dir" Orders form
            String ordersString = String.join(", ",
                    Arrays.stream(orderEntries)
                            .map(e -> e.replace(':', ' '))
                            .toArray(String[]::new));
            m.setDefaultOrder(Orders.of(ordersString));
        }
        m.setSoftDelete(anno.softDelete());
        m.setSoftDeleteField(blankToNull(anno.softDeleteField()));
        m.setActiveControl(anno.activeControl());
        m.setTimeline(anno.timeline());
        m.setIdStrategy(anno.idStrategy());
        m.setStorageType(anno.storageType());
        m.setVersionLock(anno.versionLock());
        m.setMultiTenant(anno.multiTenant());
        m.setCopyable(anno.copyable());
        m.setDataSource(blankToNull(anno.dataSource()));
        m.setBusinessKey(toList(anno.businessKey()));
        m.setPartitionField(blankToNull(anno.partitionField()));
        return m;
    }

    // ---------------------------------------------------------------- @Field

    private List<SysField> parseFields(Class<?> clazz) {
        String modelName = clazz.getSimpleName();
        List<SysField> out = new ArrayList<>();

        // Primary key is always emitted, even without @Field. Its type comes
        // from the declared `id` Java field (Long → LONG, String → STRING),
        // never from @Field(fieldType=...). See buildIdField.
        out.add(buildIdField(clazz, modelName));

        // id is emitted above (its type is inferred, not annotation-driven), so it
        // is skipped here even when it carries @Field(label = "ID").
        for (Field javaField : metadataFields(clazz)) {
            if (ModelConstant.ID.equals(javaField.getName())) {
                continue;
            }
            out.add(parseField(modelName, javaField, ormField(javaField)));
        }
        return out;
    }

    /**
     * Build the primary-key {@code id} SysField. The PK is always part of the
     * metadata surface, so it is emitted even when the {@code id} field carries
     * no {@code @Field}. The column type is inferred from the declared Java
     * field type ({@code Long → LONG}, {@code String → STRING}); a string id
     * defaults to {@link #STRING_ID_LENGTH} unless {@code @Field(length=...)}
     * overrides it. {@code @Field(fieldType=...)} on the id is rejected — the PK
     * type must follow the Java type so it stays consistent with the configured
     * {@code idStrategy} and the runtime IdProcessor.
     */
    private SysField buildIdField(Class<?> clazz, String modelName) {
        Field idJavaField = findDeclaredField(clazz, ModelConstant.ID);
        io.softa.framework.orm.annotation.Field anno = validatedIdFieldAnnotation(modelName, idJavaField);

        FieldType type = FieldType.LONG;
        if (idJavaField != null) {
            try {
                type = TypeInference.infer(idJavaField.getType(), null).fieldType();
            } catch (RuntimeException e) {
                // Unusual id types (e.g. Serializable) fall back to a Long PK.
            }
        }

        SysField f = new SysField();
        f.setModelName(modelName);
        f.setFieldName(ModelConstant.ID);
        f.setColumnName(ModelConstant.ID);
        f.setFieldType(type);
        f.setRequired(true);
        // Explicit false, like parseField: a null here would diff against the DB row's 0/false and phantom-modify every model's id row on the first boot.
        f.setAutoSequence(false);
        if (anno != null) {
            f.setDescription(checkedDescription(anno.description(), "field " + modelName + ".id"));
            if (anno.length() > 0) {
                f.setLength(anno.length());
            }
        }
        if (f.getLength() == null && type == FieldType.STRING) {
            f.setLength(STRING_ID_LENGTH);
        }
        f.setLabel(labelOf(anno != null ? anno.label() : "", ModelConstant.ID));
        return f;
    }

    /**
     * The {@code @Field} annotation on the {@code id} field ({@code null} when absent),
     * validated: {@code @Field(fieldType = ...)} on the id is rejected — the PK type
     * must follow the Java field type.
     */
    private static io.softa.framework.orm.annotation.@Nullable Field validatedIdFieldAnnotation(
            String modelName, Field idJavaField) {
        io.softa.framework.orm.annotation.Field anno =
                idJavaField != null ? ormField(idJavaField) : null;
        if (anno != null && anno.fieldType().length > 0) {
            throw new IllegalStateException(
                    "@Field(fieldType = ...) is not allowed on the id of " + modelName
                            + "; the primary-key type is inferred from the Java field type.");
        }
        if (anno != null && anno.autoSequence()) {
            throw new IllegalStateException(
                    "@Field(autoSequence = true) is not allowed on the id of " + modelName
                            + "; primary-key generation is governed by @Model(idStrategy = ...)"
                            + " and the flag would be silently ignored here.");
        }
        return anno;
    }

    /** Find a declared field by name, walking up the superclass chain. */
    private static Field findDeclaredField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // continue up the hierarchy
            }
        }
        return null;
    }

    private SysField parseField(
            String modelName,
            Field javaField,
            io.softa.framework.orm.annotation.Field anno) {

        SysField f = new SysField();
        f.setModelName(modelName);
        f.setFieldName(javaField.getName());
        f.setLabel(labelOf(anno.label(), javaField.getName()));
        f.setColumnName(StringUtils.isBlank(anno.columnName())
                ? StringTools.toUnderscoreCase(javaField.getName())
                : anno.columnName());
        validateSqlIdentifier("Column name", f.getColumnName(),
                "of field " + modelName + "." + javaField.getName(),
                "Rename the field or declare a different @Field(columnName = ...).");
        f.setDescription(checkedDescription(anno.description(),
                "field " + modelName + "." + javaField.getName()));

        // Resolve fieldType + optionSetCode + relatedModel
        TypeInference.FieldTypeResolution resolved = resolveFieldType(modelName, javaField, anno);
        f.setFieldType(resolved.fieldType());

        if (StringUtils.isNotBlank(resolved.optionSetCode())) {
            f.setOptionSetCode(resolved.optionSetCode());
        }

        // relatedModel: explicit Class > explicit name > inferred
        Class<?> relatedClass = anno.relatedModel();
        if (relatedClass != Void.class) {
            f.setRelatedModel(relatedClass.getSimpleName());
        } else if (StringUtils.isNotBlank(anno.relatedModelName())) {
            f.setRelatedModel(anno.relatedModelName());
        } else if (StringUtils.isNotBlank(resolved.relatedModel())) {
            f.setRelatedModel(resolved.relatedModel());
        }

        f.setRelatedField(blankToNull(anno.relatedField()));
        Class<?> joinClass = anno.joinModel();
        f.setJoinModel(joinClass != Void.class
                ? joinClass.getSimpleName()
                : blankToNull(anno.joinModelName()));
        f.setJoinLeft(blankToNull(anno.joinLeft()));
        f.setJoinRight(blankToNull(anno.joinRight()));
        f.setCascadedField(blankToNull(anno.cascadedField()));
        f.setFilters(blankToNull(anno.filters()));
        f.setDefaultValue(blankToNull(anno.defaultValue()));
        f.setLength(anno.length() > 0 ? anno.length() : null);
        f.setScale(anno.scale() > 0 ? anno.scale() : null);
        // Resolve the builtin type-default length/scale at the METADATA layer so
        // sys_field carries the real column width instead of null (which would
        // diverge from the VARCHAR(n) the DDL layer renders, and trip the
        // length-validation path). Safe for the annotation lane: it always
        // renders DDL via BuiltinDdlMetadataResolver, so the builtin default is
        // the effective width. Explicit @Field(length=...) still wins.
        FieldDdlDefault typeDefault = BuiltinDdlMetadataResolver.builtinDefaultFor(resolved.fieldType());
        if (typeDefault != null) {
            if (f.getLength() == null && typeDefault.length() != null) {
                f.setLength(typeDefault.length());
            }
            if (f.getScale() == null && typeDefault.scale() != null) {
                f.setScale(typeDefault.scale());
            }
        }
        f.setRequired(anno.required() || javaField.getType().isPrimitive());
        f.setReadonly(anno.readonly());
        f.setTranslatable(anno.translatable());
        f.setCopyable(anno.copyable());
        f.setUnsearchable(anno.unsearchable());
        f.setComputed(anno.computed());
        f.setExpression(blankToNull(anno.expression()));
        f.setDynamic(anno.dynamic());
        f.setEncrypted(anno.encrypted());
        // The rendered sequence value is a string (e.g. "EMP-00042"), so a non-STRING column would only fail later, at the first blank-code INSERT — reject at scan time instead. 
        // Set unconditionally (false, not null) so the diff against the DB row's 0/false never reports a phantom modification.
        if (anno.autoSequence() && resolved.fieldType() != FieldType.STRING) {
            throw new IllegalStateException(
                    "@Field(autoSequence = true) on " + modelName + "." + javaField.getName()
                            + " requires a STRING field (the rendered sequence value is a"
                            + " string), but the resolved field type is "
                            + resolved.fieldType() + ".");
        }
        if (anno.autoSequence() && (anno.dynamic() || anno.computed())) {
            throw new IllegalStateException(
                    "@Field(autoSequence = true) on " + modelName + "." + javaField.getName()
                            + " cannot be combined with dynamic or computed:"
                            + " a dynamic field is never stored and a computed field is overwritten by its expression,"
                            + " so the allocated sequence value would be silently lost.");
        }
        f.setAutoSequence(anno.autoSequence());

        f.setMaskingType(firstOrNull(anno.maskingType()));
        f.setWidgetType(firstOrNull(anno.widgetType()));
        // onDelete: only for TO_ONE relations + explicit declaration (empty array = KEEP).
        if (FieldType.TO_ONE_TYPES.contains(resolved.fieldType())) {
            f.setOnDelete(firstOrNull(anno.onDelete()));
        }

        return f;
    }

    private TypeInference.FieldTypeResolution resolveFieldType(
            String modelName,
            Field javaField,
            io.softa.framework.orm.annotation.Field anno) {

        Class<?> rawType = javaField.getType();
        Class<?> elementType = ReflectionTypes.listElementType(javaField);
        FieldType[] explicit = anno.fieldType();

        if (explicit.length == 0) {
            // No explicit override → pure inference from Java type.
            // Inference handles: enum → OPTION, List<enum> → MULTI_OPTION, etc.
            return TypeInference.infer(rawType, elementType);
        }

        FieldType type = explicit[0];

        // OPTION / MULTI_OPTION are always derived from the Java type (enum or
        // List<enum>), never declared. Writing them explicitly is invalid —
        // either redundant (Java type is already the enum, so inference would
        // produce the same result) or wrong (Java type is not an enum, in
        // which case OPTION semantics don't apply).
        if (type == FieldType.OPTION || type == FieldType.MULTI_OPTION) {
            throw new IllegalStateException(
                    "@Field(fieldType = " + type + ") on " + modelName + "."
                            + javaField.getName()
                            + " is invalid; " + type + " is always auto-derived from"
                            + " the Java type (declare the field as the actual enum"
                            + " for OPTION, or List<enum> for MULTI_OPTION).");
        }

        // Explicit relational fieldType: still derive relatedModel from the Java type
        // when the type carries it (entity → *_TO_ONE, List<entity> → *_TO_MANY), so
        // callers need not restate it. Only Long / List<Long> FK-id relations — whose
        // Java type carries no entity — still require an explicit relatedModel.
        // (relatedModel resolution keeps an explicit relatedModel= winning over this.)
        if (FieldType.TO_ONE_TYPES.contains(type) && rawType.isAnnotationPresent(Model.class)) {
            return TypeInference.FieldTypeResolution.related(type, rawType.getSimpleName());
        }
        if ((type == FieldType.ONE_TO_MANY || type == FieldType.MANY_TO_MANY)
                && elementType != null && elementType.isAnnotationPresent(Model.class)) {
            return TypeInference.FieldTypeResolution.related(type, elementType.getSimpleName());
        }

        // Other explicit fieldTypes (e.g. STRING → TEXT, or LONG → MANY_TO_ONE on a
        // raw FK id) override inference; relatedModel/optionSetCode are not derivable
        // from the Java type here and must be supplied by the caller if needed.
        //
        // An explicit TO_ONE fieldType reaching here carries a scalar Java type (the
        // entity-typed @Model form returned above): a reference-by-(code|id) FK whose
        // own Java type IS the stored value. The FK column physically mirrors the
        // referenced column (ReferenceColumnResolver), so the scalar type must match the
        // referenced field's type — a Long FK onto a String business key renders VARCHAR
        // yet cannot read the code back into the Long field at runtime. The Java type is
        // discarded right after this point (only `type` survives into metadata), so the
        // mismatch is invisible to every later layer; fail-fast here.
        if (FieldType.TO_ONE_TYPES.contains(type)) {
            validateScalarFkValueType(modelName, javaField, anno);
        }
        return TypeInference.FieldTypeResolution.of(type);
    }

    /**
     * Guard a scalar reference-by-(code|id) FK against a Java-type / referenced-column
     * mismatch. The FK column mirrors the field named by {@code relatedField} (defaulting
     * to {@code id}) on {@code relatedModel}, so the FK's own Java type must resolve to the
     * same {@link FieldType} as that field. Resolution is by reflection off the annotation's
     * {@code relatedModel} Class; a relation declared by model name only (no Class), onto a
     * missing field, or onto an un-inferable type is left to {@code ModelManager
     * .validateRelationalField} (existence / stored / unique) at runtime.
     */
    private static void validateScalarFkValueType(
            String modelName, Field javaField,
            io.softa.framework.orm.annotation.Field anno) {
        Class<?> relatedClass = anno.relatedModel();
        if (relatedClass == Void.class) {
            return;   // related model declared by name only — no Class to reflect here
        }
        String relatedFieldName = StringUtils.isNotBlank(anno.relatedField())
                ? anno.relatedField()
                : ModelConstant.ID;
        Field referenced = findDeclaredField(relatedClass, relatedFieldName);
        if (referenced == null) {
            return;   // missing referenced field — reported by ModelManager.validateRelationalField
        }
        FieldType fkType = safeInferFieldType(javaField);
        FieldType refType = safeInferFieldType(referenced);
        if (fkType == null || refType == null || fkType == refType) {
            return;
        }
        throw new IllegalStateException(
                "Reference-by-code FK " + modelName + "." + javaField.getName()
                        + " is declared as " + javaField.getType().getSimpleName()
                        + " (" + fkType + ") but references " + relatedClass.getSimpleName()
                        + "." + relatedFieldName + " of type "
                        + referenced.getType().getSimpleName() + " (" + refType + "). "
                        + "The FK's Java type must match the referenced field's type so the "
                        + "stored value round-trips; declare the FK as "
                        + referenced.getType().getSimpleName() + ".");
    }

    /** Infer a field's {@link FieldType}, returning {@code null} when it cannot be inferred. */
    private static FieldType safeInferFieldType(Field field) {
        try {
            return TypeInference.infer(field.getType(),
                    ReflectionTypes.listElementType(field)).fieldType();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ @OptionSet

    private SysOptionSet parseOptionSet(Class<?> enumClass, OptionSet anno) {
        SysOptionSet os = new SysOptionSet();
        os.setOptionSetCode(enumClass.getSimpleName());
        os.setLabel(labelOf(anno.label(), enumClass.getSimpleName()));
        os.setDescription(checkedDescription(anno.description(),
                "option set " + enumClass.getSimpleName()));
        return os;
    }

    private List<SysOptionItem> parseOptionItems(Class<?> enumClass) {
        String optionSetCode = enumClass.getSimpleName();
        List<SysOptionItem> out = new ArrayList<>();

        Object[] constants = enumClass.getEnumConstants();
        if (constants == null) {
            return out;
        }
        // itemCode (the @JsonValue) keys the sys_option_item row — two constants
        // resolving to one code would silently collapse to a single row downstream.
        Map<String, String> constantByItemCode = new LinkedHashMap<>();
        for (int i = 0; i < constants.length; i++) {
            Enum<?> constant = (Enum<?>) constants[i];
            OptionItem itemAnno = readOptionItemAnnotation(enumClass, constant);
            SysOptionItem item = buildOptionItem(optionSetCode, constant, itemAnno, i);
            String prev = constantByItemCode.putIfAbsent(item.getItemCode(), constant.name());
            if (prev != null) {
                throw new IllegalStateException("Duplicate itemCode '" + item.getItemCode()
                        + "' on @OptionSet " + enumClass.getName() + ": constants " + prev
                        + " and " + constant.name() + " resolve to the same code.");
            }
            out.add(item);
        }
        return out;
    }

    /**
     * Read the {@link OptionItem} annotation declared on the enum constant's
     * implicit field (e.g. {@code public static final FOO}). Returns {@code null}
     * if absent — all attributes then fall back to defaults.
     */
    private OptionItem readOptionItemAnnotation(Class<?> enumClass, Enum<?> constant) {
        try {
            Field constantField = enumClass.getField(constant.name());
            return constantField.getAnnotation(OptionItem.class);
        } catch (NoSuchFieldException e) {
            // Should be unreachable: enum constants always have a matching field.
            return null;
        }
    }

    private SysOptionItem buildOptionItem(
            String optionSetCode,
            Enum<?> constant,
            OptionItem anno,
            int ordinal) {

        String itemCode = JsonValueResolver.resolveItemCode(constant);
        SysOptionItem item = new SysOptionItem();
        item.setOptionSetCode(optionSetCode);
        item.setItemCode(itemCode);
        item.setLabel(labelOf(anno != null ? anno.label() : "", constant.name()));
        item.setSequence(anno != null && anno.sequence() >= 0 ? anno.sequence() : ordinal + 1);
        if (anno != null) {
            item.setDescription(checkedDescription(anno.description(),
                    "option item " + optionSetCode + "." + itemCode));
            item.setParentItemCode(blankToNull(anno.parentItemCode()));
            item.setItemTone(firstOrNull(anno.itemTone()));
            item.setItemIcon(firstOrNull(anno.itemIcon()));
        }
        return item;
    }

    // ----------------------------------------------------------- @Index

    /**
     * Read {@code @Index} (repeatable) declarations on the class. Each becomes
     * a {@link io.softa.starter.metadata.entity.SysModelIndex} row.
     *
     * <p>Field name validation: every {@code fields} entry must match a
     * declared {@link io.softa.framework.orm.annotation.Field}-annotated
     * field on the same class. Mismatches throw to surface AI / human typos
     * at scan time rather than at DDL execution.
     *
     * <p>Index name derivation: if {@code @Index.indexName()} is empty, derive
     * {@code uk_<table>_<col1>_<col2>...} (unique) or
     * {@code idx_<table>_<col1>_<col2>...}. The name (explicit or derived) is
     * rejected when it exceeds the {@code SysModelIndex.indexName} width (60),
     * so it fits both MySQL (64) and PostgreSQL (63) identifier limits;
     * global-uniqueness of index names is enforced later at metadata load.
     *
     * <p>Message: {@code @Index.message()} is stored verbatim (null when blank).
     * It is rejected on a non-unique index (a message is only shown on a
     * unique-constraint violation) or when longer than the
     * {@code SysModelIndex.message} width.
     */
    private List<SysModelIndex> parseIndexes(
            Class<?> clazz, String tableName, List<SysField> parsedFields) {
        Index[] indexAnnos =
                clazz.getAnnotationsByType(Index.class);
        if (indexAnnos.length == 0) {
            return List.of();
        }
        String modelName = clazz.getSimpleName();
        // fieldName → parsed-field lookup for column resolution + validation
        // (parsedFields is this class's own parseFields output)
        Map<String, SysField> fieldsByName = new LinkedHashMap<>();
        for (SysField f : parsedFields) {
            fieldsByName.put(f.getFieldName(), f);
        }

        // indexName keys the sys_model_index row within the model — two @Index
        // declarations deriving/declaring the same name would silently collapse
        // to one row downstream (global uniqueness across models is enforced at
        // metadata load).
        Map<String, SysModelIndex> byName = new LinkedHashMap<>();
        for (Index anno : indexAnnos) {
            SysModelIndex idx = buildIndex(modelName, tableName, anno, fieldsByName);
            if (byName.putIfAbsent(idx.getIndexName(), idx) != null) {
                throw new IllegalStateException(
                        "@Index on " + modelName + " declares index name '" + idx.getIndexName()
                                + "' twice; each @Index needs a distinct (explicit or derived) name.");
            }
        }
        return new ArrayList<>(byName.values());
    }

    private SysModelIndex buildIndex(
            String modelName,
            String tableName,
            Index anno,
            Map<String, SysField> fieldsByName) {

        String[] fields = anno.fields();
        if (fields == null || fields.length == 0) {
            throw new IllegalStateException(
                    "@Index on " + modelName + " has empty fields[]; must declare at least one field");
        }
        // Validate referenced fields exist on the model AND are physically stored —
        // a dynamic or TO_MANY field has no column, so the index DDL would reference
        // a nonexistent column and fail at execution.
        List<String> columnNames = new ArrayList<>();
        for (String fieldName : fields) {
            SysField field = fieldsByName.get(fieldName);
            if (field == null) {
                throw new IllegalStateException(
                        "@Index on " + modelName + " references unknown field '" + fieldName
                                + "'; declared @Field-annotated fields are: " + fieldsByName.keySet());
            }
            if (Boolean.TRUE.equals(field.getDynamic())
                    || FieldType.TO_MANY_TYPES.contains(field.getFieldType())) {
                throw new IllegalStateException(
                        "@Index on " + modelName + " references field '" + fieldName
                                + "' which has no physical column ("
                                + (Boolean.TRUE.equals(field.getDynamic()) ? "dynamic" : field.getFieldType())
                                + "); only stored fields can be indexed.");
            }
            columnNames.add(field.getColumnName());
        }

        boolean derived = StringUtils.isBlank(anno.indexName());
        String indexName = derived
                ? deriveIndexName(tableName, columnNames, anno.unique())
                : anno.indexName();
        validateSqlIdentifier("Index name", indexName, "of @Index on " + modelName,
                "Declare a different indexName.");
        if (indexName.length() > INDEX_NAME_MAX) {
            throw new IllegalStateException(
                    "@Index on " + modelName + " index name '" + indexName + "' exceeds "
                            + INDEX_NAME_MAX + " chars (sys_model_index.index_name width)"
                            + (derived ? "; declare a shorter explicit indexName" : ""));
        }
        if (!anno.unique() && StringUtils.isNotBlank(anno.message())) {
            throw new IllegalStateException(
                    "@Index on " + modelName + " (" + indexName + ") declares message but is not unique; "
                            + "a message is only shown on a unique-constraint violation");
        }
        if (anno.message().length() > MESSAGE_MAX) {
            throw new IllegalStateException(
                    "@Index on " + modelName + " (" + indexName + ") message exceeds "
                            + MESSAGE_MAX + " chars (sys_model_index.message width)");
        }

        SysModelIndex idx = new SysModelIndex();
        idx.setModelName(modelName);
        idx.setIndexName(indexName);
        idx.setIndexFields(new ArrayList<>(Arrays.asList(fields)));
        idx.setUniqueIndex(anno.unique());
        idx.setMessage(StringUtils.isBlank(anno.message()) ? null : anno.message());
        return idx;
    }

    /**
     * {@code uk_<table>_<col1>_<col2>...} or {@code idx_<table>_<col1>_<col2>...}. Not
     * truncated: an over-length derived name is rejected by the caller so the developer
     * supplies a shorter explicit {@code indexName} (silent truncation could collide).
     */
    private static String deriveIndexName(String tableName, List<String> columnNames, boolean unique) {
        String prefix = unique ? "uk_" : "idx_";
        StringBuilder sb = new StringBuilder(prefix).append(tableName);
        for (String col : columnNames) {
            sb.append('_').append(col);
        }
        return sb.toString();
    }

    /**
     * Validate that {@code @Model.displayName} / {@code searchName} /
     * {@code defaultOrder} reference fields actually declared on the model
     * (mirrors {@code @Index} field validation), surfacing typos at scan time
     * rather than at query time. {@code defaultOrder} entries may be
     * {@code "field"}, {@code "field:dir"}, or {@code "field dir"} — the field
     * token (first) is validated.
     */
    private void validateModelFieldRefs(Class<?> clazz, Model anno, List<SysField> classFields) {
        String modelName = clazz.getSimpleName();
        Set<String> fieldNames = new HashSet<>();
        for (SysField f : classFields) {
            fieldNames.add(f.getFieldName());
        }
        checkFieldRefs(modelName, "displayName", anno.displayName(), fieldNames);
        checkFieldRefs(modelName, "searchName", anno.searchName(), fieldNames);
        String[] orders = anno.defaultOrder();
        String[] orderFields = new String[orders.length];
        for (int i = 0; i < orders.length; i++) {
            String[] parts = orders[i].trim().split("[:\\s]+");
            orderFields[i] = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : orders[i];
        }
        checkFieldRefs(modelName, "defaultOrder", orderFields, fieldNames);
    }

    private void checkFieldRefs(String modelName, String attr, String[] refs, Set<String> fieldNames) {
        if (refs == null) {
            return;
        }
        for (String ref : refs) {
            if (StringUtils.isNotBlank(ref) && !fieldNames.contains(ref)) {
                throw new IllegalStateException(
                        "@Model(" + attr + ") on " + modelName + " references unknown field '"
                                + ref + "'; declared @Field-annotated fields are: " + fieldNames);
            }
        }
    }

    // -------------------------------------------------------------- helpers

    /** The ORM {@code @Field} annotation of a Java field ({@code null} when absent). */
    private static io.softa.framework.orm.annotation.@Nullable Field ormField(Field javaField) {
        return javaField.getAnnotation(io.softa.framework.orm.annotation.Field.class);
    }

    /**
     * First element or {@code null}. Annotation attributes use {@code T[]} as
     * "optional": an annotation cannot declare a {@code null} default, so an
     * empty array means "unset".
     */
    private static <T> T firstOrNull(T[] arr) {
        return arr.length > 0 ? arr[0] : null;
    }

    /** The declared label, or {@code humanize(fallbackName)} when blank — the label convention. */
    private static String labelOf(String declared, String fallbackName) {
        return StringUtils.isBlank(declared) ? StringTools.humanize(fallbackName) : declared;
    }

    private static void validateSqlIdentifier(
            String label, String identifier, String owner, String action) {
        if (!StringTools.isTableOrColumn(identifier)) {
            throw new IllegalStateException(label + " '" + identifier + "' " + owner
                    + " is not a valid SQL identifier (identifiers render unquoted); "
                    + "it must satisfy StringTools.isTableOrColumn. " + action);
        }
        if (SqlReservedWords.isReserved(identifier)) {
            throw new IllegalStateException(label + " '" + identifier + "' " + owner
                    + " is a reserved SQL keyword (identifiers render unquoted); " + action);
        }
    }

    private static String blankToNull(String s) {
        return StringUtils.isBlank(s) ? null : s;
    }

    /**
     * {@link #blankToNull} for {@code description} attributes, rejecting values the
     * sys_* / design_* catalog columns cannot hold — at parse time, so the boot fails
     * with the owner named, before any DDL side effect and instead of a raw SQL error
     * while writing catalog rows.
     */
    private static String checkedDescription(String description, String owner) {
        String value = blankToNull(description);
        if (value != null && value.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalStateException("Description of " + owner + " is "
                    + value.length() + " characters, exceeding the " + DESCRIPTION_MAX_LENGTH
                    + "-char catalog limit. Keep the description a concise user-facing summary"
                    + " (it renders as a form tooltip and in API docs); move design rationale"
                    + " and contributor notes to Javadoc.");
        }
        return value;
    }

    private static List<String> toList(String[] arr) {
        if (arr == null || arr.length == 0) {
            return null;
        }
        return new ArrayList<>(Arrays.asList(arr));
    }
}
