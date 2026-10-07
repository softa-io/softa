package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.excel.imports.handler.*;

@Component
public class ImportHandlerFactory {

    /**
     * Create the field handlers for the import template.
     * Dotted-path <b>relation lookup</b> fields (e.g. deptId.code) are skipped here because they are
     * resolved by {@link RelationLookupResolver} against the related model's business key. Dotted-path
     * <b>OneToOne</b> fields are different: they describe the main row's own sub-record and are written
     * inline, so they still need a handler — see {@link #createNestedOneToOneHandler}.
     */
    public List<BaseImportHandler> createHandlers(ImportTemplateDTO importTemplateDTO) {
        String modelName = importTemplateDTO.getModelName();
        List<BaseImportHandler> handlers = new ArrayList<>();
        for (ImportFieldDTO importFieldDTO : importTemplateDTO.getImportFields()) {
            String fieldName = importFieldDTO.getFieldName();
            if (fieldName.contains(".")) {
                BaseImportHandler nestedHandler = createNestedOneToOneHandler(modelName, fieldName, importFieldDTO);
                if (nestedHandler != null) {
                    handlers.add(nestedHandler);
                    continue;
                }
                // A relation lookup path. RelationLookupResolver resolves the value, but nothing was
                // checking that a mandatory relation was filled in at all — see LookupRequiredHandler.
                BaseImportHandler requiredHandler =
                        createLookupRequiredHandler(modelName, fieldName, importFieldDTO);
                if (requiredHandler != null) {
                    handlers.add(requiredHandler);
                }
                continue;
            }
            if (!ModelManager.existField(modelName, fieldName)) {
                continue;
            }
            MetaField metaField = ModelManager.getModelField(modelName, fieldName);
            promoteModelRequired(importFieldDTO, metaField);
            handlers.add(createHandler(metaField, importFieldDTO));
        }
        return handlers;
    }

    /**
     * Carry the model's requiredness onto a column the template left optional — but only when a blank
     * cell would be written.
     *
     * <p>A cell-level check cannot tell a create from an update. With {@code ignoreEmpty} on, a blank
     * cell is dropped from the row instead of written as null, which on an update means "keep the
     * existing value" — and checking the model's requiredness here rejected exactly that, so a file
     * of corrections had to restate every mandatory column of every row. The ORM still enforces the
     * requirement where it can tell the two apart: a create without the field is refused, an update
     * that does not carry it is not. With {@code ignoreEmpty} off the blank is written as null, the
     * ORM would refuse it either way, and checking here only buys the column's own name in the error.
     *
     * <p>The template's own {@code required} is never relaxed: it is a rule the template states for
     * every row, create or update.
     */
    private static void promoteModelRequired(ImportFieldDTO importFieldDTO, MetaField metaField) {
        if (Boolean.TRUE.equals(importFieldDTO.getRequired()) || Boolean.TRUE.equals(importFieldDTO.getIgnoreEmpty())) {
            return;
        }
        importFieldDTO.setRequired(metaField.isRequired());
    }

    /**
     * Create a handler for a nested OneToOne sub-field, e.g. {@code employeeProfileId.gender}.
     *
     * <p>A OneToOne dotted path is not a lookup: the related row is the main row's own 1:1 sub-record,
     * written inline by the ORM cascade. Its cells therefore still need the standard conversion the
     * flat columns get — dates parsed, options mapped — which the lookup path used to supply as a side
     * effect of normalizing the business key. The metadata comes from the <i>related</i> model while
     * the column stays keyed by the dotted path, hence {@link BaseImportHandler#rowKey}.
     *
     * <p>{@code required} is deliberately NOT inherited from the sub-field's metadata: a blank cell on
     * a OneToOne sub-field means "leave this value alone" on update, and the ORM still enforces the
     * requirement when the sub-record is created.
     *
     * @return the handler keyed by the dotted path, or null when the path is a relation lookup instead
     */
    /**
     * A required-only handler for a lookup column, or null when the column is not mandatory.
     *
     * <p>Registered only when the relation really is required: an optional column keeps having no
     * handler at all, so this adds a check without pulling those columns into the empty-value paths
     * (defaultValue / ignoreEmpty) that a handler would otherwise start participating in.
     */
    BaseImportHandler createLookupRequiredHandler(String modelName, String fieldName,
                                                  ImportFieldDTO importFieldDTO) {
        MetaField metaField = addressedRelation(modelName, fieldName);
        if (metaField == null) {
            return null;
        }
        promoteModelRequired(importFieldDTO, metaField);
        if (!Boolean.TRUE.equals(importFieldDTO.getRequired())) {
            return null;
        }
        return new LookupRequiredHandler(metaField, importFieldDTO, fieldName);
    }

    /**
     * The relation a lookup column actually names — the second-to-last segment, not the first.
     *
     * <p>The last segment is how the relation is addressed (a name, a code), so the column is about
     * the one before it: {@code legalEntityId.name} is about {@code legalEntityId}, and
     * {@code employeeProfileId.passType.name} is about {@code passType} on EmployeeProfile.
     *
     * <p>Reading the first segment instead made a required root spread down every path that ran
     * through it. {@code Employee.employeeProfileId} is required — an employee must have a profile —
     * and that turned all five of the Singapore template's {@code employeeProfileId.*.name} columns
     * mandatory, Pass Type among them. Singapore citizens and PRs hold no pass and the value domain
     * offers nothing for them, so those employees could not be imported at all, by any spelling.
     *
     * <p>"An employee must have a profile" is not "every column reaching through the profile is
     * mandatory", and only the addressed field can say which. The sibling
     * {@code createSubFieldHandler} already declines to inherit for the same reason.
     */
    private MetaField addressedRelation(String modelName, String fieldName) {
        String[] segments = fieldName.split("\\.");
        if (segments.length < 2) {
            return null;
        }
        String currentModel = modelName;
        // Walk to the model the addressed field lives on: every segment before the last two is a
        // relation being traversed, not the one the column is about.
        for (int i = 0; i < segments.length - 2; i++) {
            if (!ModelManager.existField(currentModel, segments[i])) {
                return null;
            }
            currentModel = ModelManager.getModelField(currentModel, segments[i]).getRelatedModel();
            if (currentModel == null || !ModelManager.existModel(currentModel)) {
                return null;
            }
        }
        String addressed = segments[segments.length - 2];
        if (!ModelManager.existField(currentModel, addressed)) {
            return null;
        }
        return ModelManager.getModelField(currentModel, addressed);
    }

    BaseImportHandler createNestedOneToOneHandler(String modelName, String fieldName, ImportFieldDTO importFieldDTO) {
        String[] parts = fieldName.split("\\.");
        if (parts.length != 2 || !ModelManager.existField(modelName, parts[0])) {
            return null;
        }
        MetaField rootMetaField = ModelManager.getModelField(modelName, parts[0]);
        // A blank relatedModel is a misconfigured template: fall through so RelationLookupResolver
        // reports it by name, rather than failing here on an unknown model.
        if (!FieldType.ONE_TO_ONE.equals(rootMetaField.getFieldType())
                || StringUtils.isBlank(rootMetaField.getRelatedModel())
                || !ModelManager.existField(rootMetaField.getRelatedModel(), parts[1])) {
            return null;
        }
        MetaField subMetaField = ModelManager.getModelField(rootMetaField.getRelatedModel(), parts[1]);
        return createHandler(subMetaField, importFieldDTO).rowKey(fieldName);
    }

    BaseImportHandler createHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        return switch (metaField.getFieldType()) {
            case BOOLEAN -> new BooleanHandler(metaField, importFieldDTO);
            case DATE -> new DateHandler(metaField, importFieldDTO);
            case DATE_TIME -> new DateTimeHandler(metaField, importFieldDTO);
            case TIME -> new TimeHandler(metaField, importFieldDTO);
            case MULTI_OPTION -> new MultiOptionHandler(metaField, importFieldDTO);
            case OPTION -> new OptionHandler(metaField, importFieldDTO);
            case INTEGER, LONG, DOUBLE, BIG_DECIMAL -> new NumberHandler(metaField, importFieldDTO);
            case MANY_TO_ONE, ONE_TO_ONE -> createToOneHandler(metaField, importFieldDTO);
            default -> new DefaultHandler(metaField, importFieldDTO);
        };
    }

    /**
     * A to-one column mapped by its bare field name imports the related row's <b>id</b>. When that id is
     * numeric, guard it so a pasted display value is reported by column name instead of reaching the
     * write and surfacing as the JDK's bare {@code For input string: "..."}.
     *
     * <p>A code-as-id master keeps the default handler: its id IS the portable code, so a bare column is
     * the correct mapping there and needs no conversion. {@code relatedFieldType} is the resolved
     * physical type of the referenced id, materialized at reconciliation time; when it is absent (a
     * template built before that column was populated) the column is left alone rather than guessed at.
     */
    private BaseImportHandler createToOneHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        if (!FieldType.LONG.equals(metaField.getRelatedFieldType())) {
            return new DefaultHandler(metaField, importFieldDTO);
        }
        return new RelationIdHandler(metaField, importFieldDTO, lookupHint(metaField));
    }

    /**
     * The dotted path to suggest: the related model's first business key, falling back to its first
     * displayName field, and to a generic hint when the model declares neither.
     *
     * <p>Key components the field itself already pins are dropped first. A composite key is the reason:
     * {@code TenantOptionItem} is keyed by {@code {optionSetCode, itemCode}}, and {@code Department.orgType}
     * declares {@code ["optionSetCode", "=", "OrganizationType"]} — so suggesting {@code orgType.optionSetCode}
     * asks the user to restate a constant and names a column that matches every item in the set. What is
     * left after the constraint is what actually identifies a row.
     */
    private String lookupHint(MetaField metaField) {
        String relatedModel = metaField.getRelatedModel();
        List<String> keys = List.of();
        if (StringUtils.isNotBlank(relatedModel) && ModelManager.existModel(relatedModel)) {
            MetaModel meta = ModelManager.getModel(relatedModel);
            keys = CollectionUtils.isEmpty(meta.getBusinessKey()) ? meta.getDisplayName() : meta.getBusinessKey();
        }
        keys = dropPinnedKeys(keys, metaField);
        String key = CollectionUtils.isEmpty(keys) ? "<businessKey>" : keys.getFirst();
        return metaField.getFieldName() + "." + key;
    }

    /**
     * {@code keys} minus the components the field's declared filters already constrain. Returns
     * {@code keys} unchanged when the field declares no filters, or when every component is pinned —
     * in that second case there is nothing left to name, and reporting the first key is more useful
     * than a truncated path.
     */
    private List<String> dropPinnedKeys(List<String> keys, MetaField metaField) {
        if (CollectionUtils.isEmpty(keys) || StringUtils.isBlank(metaField.getFilters())) {
            return keys;
        }
        Filters declared = Filters.of(metaField.getFilters());
        if (declared == null) {
            return keys;
        }
        Set<String> pinned = declared.extractFields();
        List<String> free = keys.stream().filter(k -> !pinned.contains(k)).toList();
        return free.isEmpty() ? keys : free;
    }
}
