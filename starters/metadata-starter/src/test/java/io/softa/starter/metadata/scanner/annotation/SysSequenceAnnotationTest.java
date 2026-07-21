package io.softa.starter.metadata.scanner.annotation;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.entity.SysOptionItem;
import io.softa.starter.metadata.enums.ResetCadence;
import io.softa.starter.metadata.enums.SequenceMode;
import io.softa.starter.metadata.entity.SysSequence;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SysSequence} is annotation-managed like the catalog models — this is
 * what makes the sequence feature self-bootstrapping (metadata + table via the
 * scanner instead of the retired out-of-band SQL). Locks the parse result:
 * multi-tenant, non-copyable, unique (tenantId, code), OPTION inference for
 * the two policy enums.
 */
class SysSequenceAnnotationTest {

    private final AnnotationParser parser = new AnnotationParser();

    private AnnotationScanResult parse() {
        return parser.parse(List.of(SysSequence.class),
                List.of(SequenceMode.class, ResetCadence.class));
    }

    @Test
    void model_isMultiTenant_nonCopyable_keyedByCode() {
        SysModel m = parse().models().getFirst();
        assertEquals("SysSequence", m.getModelName());
        assertEquals("sys_sequence", m.getTableName());
        assertEquals(Boolean.TRUE, m.getMultiTenant());
        assertEquals(Boolean.FALSE, m.getCopyable(),
                "duplicating a sequence config would clone the counter state");
        assertEquals(List.of("code"), m.getBusinessKey());
        assertEquals(IdStrategy.DB_AUTO_ID, m.getIdStrategy());
    }

    @Test
    void tenantAndCode_formAUniqueIndex() {
        SysModelIndex uk = parse().modelIndexes().getFirst();
        assertEquals("uk_sys_sequence_tenant_id_code", uk.getIndexName());
        assertEquals(List.of("tenantId", "code"), uk.getIndexFields());
        assertEquals(Boolean.TRUE, uk.getUniqueIndex(),
                "duplicate (tenant, code) rows would split the counter and issue duplicate numbers");
    }

    @Test
    void policyEnums_inferAsOptionFields() {
        List<SysField> fields = parse().fields();
        SysField mode = byFieldName(fields, "mode");
        assertEquals(FieldType.OPTION, mode.getFieldType());
        assertEquals("SequenceMode", mode.getOptionSetCode());
        SysField cadence = byFieldName(fields, "resetCadence");
        assertEquals(FieldType.OPTION, cadence.getFieldType());
        assertEquals("ResetCadence", cadence.getOptionSetCode());
    }

    @Test
    void allocationInputs_areRequired() {
        List<SysField> fields = parse().fields();
        for (String required : List.of("code", "template", "startValue", "incrementStep",
                "currentValue", "resetCadence", "mode")) {
            assertEquals(Boolean.TRUE, byFieldName(fields, required).getRequired(),
                    required + " is dereferenced by the allocator and must be present");
        }
        assertEquals(Boolean.FALSE, byFieldName(fields, "lastResetKey").getRequired(),
                "lastResetKey is null until the first reset");
    }

    @Test
    void optionItems_carryTheJsonValueCodes() {
        List<SysOptionItem> items = parse().optionItems();
        Set<String> modeCodes = items.stream()
                .filter(i -> "SequenceMode".equals(i.getOptionSetCode()))
                .map(SysOptionItem::getItemCode)
                .collect(Collectors.toSet());
        assertEquals(Set.of("NoGap", "AllowGap"), modeCodes,
                "itemCode = @JsonValue, matching what the ORM stores and API payloads carry");
        Set<String> cadenceCodes = items.stream()
                .filter(i -> "ResetCadence".equals(i.getOptionSetCode()))
                .map(SysOptionItem::getItemCode)
                .collect(Collectors.toSet());
        assertEquals(Set.of("None", "Yearly", "Monthly", "Daily"), cadenceCodes);
    }

    private static SysField byFieldName(List<SysField> fields, String name) {
        return fields.stream()
                .filter(f -> name.equals(f.getFieldName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + name));
    }
}
