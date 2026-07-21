package io.softa.starter.metadata.scanner.annotation;

import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.metadata.entity.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AnnotationParser}.
 */
class AnnotationParserTest {

    private final AnnotationParser parser = new AnnotationParser();

    // ------- fixtures ----------------------------------------------------

    @OptionSet(label = "Customer Tier")
    enum Tier {
        @OptionItem(label = "Gold", sequence = 1)
        GOLD("g"),
        SILVER("s");

        @JsonValue
        private final String code;

        Tier(String code) {
            this.code = code;
        }
    }

    @Model(
            label = "Customer",
            tableName = "biz_customer",
            softDelete = true,
            multiTenant = false
    )
    @SuppressWarnings("unused")
    static class Customer extends AuditableModel {
        @Field
        private Long id;

        @Field(label = "Customer Name", length = 64, required = true)
        private String name;

        @Field(copyable = false)
        private LocalDate signupDate;

        @Field
        private Tier tier;

        // No @Field annotation → should be skipped
        private String internalNote;

        @Override
        public Serializable getId() {
            return id;
        }
    }

    // ------- @Model parsing ---------------------------------------------

    @Test
    void parsesModel_attributesAndDerivedTableName() {
        AnnotationScanResult result = parser.parse(List.of(Customer.class), List.of());

        assertEquals(1, result.models().size());
        SysModel m = result.models().get(0);
        assertEquals("Customer", m.getModelName());
        assertEquals("Customer", m.getLabel());
        assertEquals("biz_customer", m.getTableName());
        assertTrue(m.getSoftDelete());
        assertEquals(false, m.getMultiTenant());
        assertEquals(Boolean.TRUE, m.getCopyable(), "copyable defaults to true");
    }

    @Test
    void modelCopyable_falseIsParsed() {
        @Model(copyable = false)
        @SuppressWarnings("unused")
        class AuditTrail extends AuditableModel {
            @Override
            public Serializable getId() {
                return null;
            }
        }
        SysModel m = parser.parse(List.of(AuditTrail.class), List.of()).models().get(0);
        assertEquals(Boolean.FALSE, m.getCopyable());
    }

    @Test
    void fieldCopyable_defaultsTrue_andParsesExplicitFalse() {
        AnnotationScanResult result = parser.parse(List.of(Customer.class), List.of());
        assertEquals(Boolean.TRUE, byFieldName(result.fields(), "name").getCopyable());
        assertEquals(Boolean.FALSE, byFieldName(result.fields(), "signupDate").getCopyable());
        // audit fields inherited from AuditableModel carry copyable = false
        assertEquals(Boolean.FALSE, byFieldName(result.fields(), "createdTime").getCopyable());
    }

    @Test
    void resolvesTypeDefaultLengthAndScale_atMetadataLayer_whenAnnotationOmitsThem() {
        @Model
        @SuppressWarnings("unused")
        class Pricing extends AuditableModel {
            @Field
            private Long id;
            @Field
            private String title;                       // STRING → 64
            @Field
            private Tier tier;                          // OPTION → 64
            @Field
            private BigDecimal amount;        // BIG_DECIMAL → length 32, scale 8
            @Field(length = 200)
            private String note;                        // explicit length wins
            @Override
            public Serializable getId() {
                return id;
            }
        }
        AnnotationScanResult r = parser.parse(List.of(Pricing.class), List.of());
        // sys_field is authoritative: type-default length/scale resolved at parse time, not left null.
        assertEquals(Integer.valueOf(64), byFieldName(r.fields(), "title").getLength());
        assertEquals(Integer.valueOf(64), byFieldName(r.fields(), "tier").getLength());
        assertEquals(Integer.valueOf(32), byFieldName(r.fields(), "amount").getLength());
        assertEquals(Integer.valueOf(8), byFieldName(r.fields(), "amount").getScale());
        assertEquals(Integer.valueOf(200), byFieldName(r.fields(), "note").getLength(),
                "explicit @Field(length) wins over the type default");
        assertNull(byFieldName(r.fields(), "id").getLength(),
                "a Long PK has no length default");
    }

    @Test
    void tableName_defaultsToSnakeCaseModelName_whenAnnotationEmpty() {
        @Model
        @SuppressWarnings("unused")
        class OrderLine extends AuditableModel {
            @Override
            public Serializable getId() {
                return null;
            }
        }
        SysModel m = parser.parse(List.of(OrderLine.class), List.of()).models().get(0);
        assertEquals("order_line", m.getTableName());
    }

    // ------- @Field parsing ---------------------------------------------

    @Test
    void parsesFields_includingDeclaredOnly_skippingUnannotated() {
        AnnotationScanResult result = parser.parse(List.of(Customer.class), List.of());

        // Business fields: id (PK, always emitted), name, signupDate, tier.
        // `internalNote` has no @Field → skipped. The 6 audit fields now carry
        // @Field on AuditableModel and are picked up via the superclass walk,
        // so 4 + 6 = 10.
        assertEquals(10, result.fields().size());
        Set<String> names = result.fields().stream()
                .map(SysField::getFieldName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("id", "name", "signupDate", "tier",
                "createdTime", "createdId", "createdBy",
                "updatedTime", "updatedId", "updatedBy"), names);
    }

    @Test
    void fieldType_inferred_fromJavaType_includingEnumOptionSetCode() {
        AnnotationScanResult result = parser.parse(List.of(Customer.class), List.of());
        SysField tier = byFieldName(result.fields(), "tier");
        assertEquals(FieldType.OPTION, tier.getFieldType());
        assertEquals("Tier", tier.getOptionSetCode());
    }

    @Test
    void columnName_defaultsToSnakeCase_andLengthRequiredPassThrough() {
        AnnotationScanResult result = parser.parse(List.of(Customer.class), List.of());
        SysField signupDate = byFieldName(result.fields(), "signupDate");
        assertEquals("signup_date", signupDate.getColumnName());

        SysField name = byFieldName(result.fields(), "name");
        assertEquals(64, name.getLength());
        assertTrue(name.getRequired());
    }

    @Test
    void auditFields_areIncludedFromBaseClass() {
        // Customer extends AuditableModel; the 6 audit fields carry @Field on the
        // base class and the parser walks the superclass chain, so they are
        // emitted for every model with their inferred types.
        AnnotationScanResult result = parser.parse(List.of(Customer.class), List.of());
        Set<String> names = result.fields().stream()
                .map(SysField::getFieldName)
                .collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                        "createdTime", "createdId", "createdBy",
                        "updatedTime", "updatedId", "updatedBy")),
                "audit fields should be parsed from AuditableModel; got " + names);
        SysField createdTime = byFieldName(result.fields(), "createdTime");
        assertEquals(FieldType.DATE_TIME, createdTime.getFieldType());
        assertTrue(createdTime.getReadonly());
    }

    // ------- @OptionSet / @OptionItem -----------------------------------

    @Test
    void parsesOptionSet_andEachConstantBecomesItem() {
        AnnotationScanResult result = parser.parse(List.of(), List.of(Tier.class));

        assertEquals(1, result.optionSets().size());
        SysOptionSet os = result.optionSets().get(0);
        assertEquals("Tier", os.getOptionSetCode());
        assertEquals("Customer Tier", os.getLabel());

        assertEquals(2, result.optionItems().size());
        SysOptionItem gold = byItemCode(result.optionItems(), "g");
        assertEquals("Gold", gold.getLabel());
        assertEquals(1, gold.getSequence());

        // Silver has no @OptionItem — label falls back to the humanized
        // constant name ("SILVER" → "Silver") and sequence to ordinal+1.
        SysOptionItem silver = byItemCode(result.optionItems(), "s");
        assertEquals("Silver", silver.getLabel());
        assertEquals(2, silver.getSequence());
    }

    @Test
    void optionItem_withBlankLabel_fallsBackToHumanizedConstantName() {
        // Blank/absent @OptionItem label → humanized enum constant name.
        @OptionSet
        @SuppressWarnings("unused")
        enum BlankNameSet {
            @OptionItem(sequence = 1) // label left blank
            FOO("foo"),
            BAR("bar"); // no @OptionItem at all

            @JsonValue
            private final String code;

            BlankNameSet(String code) {
                this.code = code;
            }
        }
        AnnotationScanResult result = parser.parse(List.of(), List.of(BlankNameSet.class));
        SysOptionItem foo = byItemCode(result.optionItems(), "foo");
        SysOptionItem bar = byItemCode(result.optionItems(), "bar");
        assertEquals("Foo", foo.getLabel(), "blank label on annotated constant → humanized constant name");
        assertEquals("Bar", bar.getLabel(), "no @OptionItem → humanized constant name");
    }

    @Test
    void emptyInputs_yieldEmptyResult() {
        AnnotationScanResult result = parser.parse(List.of(), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void blankAnnotationAttribute_becomesNull_notEmptyString() {
        AnnotationScanResult result = parser.parse(List.of(Customer.class), List.of());
        SysField signupDate = byFieldName(result.fields(), "signupDate");
        // description default = "" — parser converts blank to null (not "")
        assertNull(signupDate.getDescription());
        // label has a humanize fallback: blank → humanized field name
        assertEquals("Signup Date", signupDate.getLabel());
    }

    // ------- description length is validated at parse time --------------
    // The sys_*/design_* catalog stores description in VARCHAR(512) columns;
    // the parser fail-fasts an oversized description with the owner named,
    // before any DDL side effect and instead of a SQL error mid-reconciliation.

    /** 64 chars; ×8 = exactly the 512-char catalog limit. */
    private static final String CHUNK_64 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String DESC_512 = CHUNK_64 + CHUNK_64 + CHUNK_64 + CHUNK_64
            + CHUNK_64 + CHUNK_64 + CHUNK_64 + CHUNK_64;

    @Model
    @SuppressWarnings("unused")
    static class DescriptionAtLimitIsAccepted extends AuditableModel {
        @Field(description = DESC_512)
        private String remark;
        @Override public Serializable getId() { return null; }
    }

    @Model
    @SuppressWarnings("unused")
    static class OversizedFieldDescriptionIsRejected extends AuditableModel {
        @Field(description = DESC_512 + "x")
        private String remark;
        @Override public Serializable getId() { return null; }
    }

    @Test
    void fieldDescription_atCatalogLimit_isAccepted() {
        AnnotationScanResult result =
                parser.parse(List.of(DescriptionAtLimitIsAccepted.class), List.of());
        assertEquals(DESC_512, byFieldName(result.fields(), "remark").getDescription());
    }

    @Test
    void fieldDescription_overCatalogLimit_isRejectedAtParse() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(OversizedFieldDescriptionIsRejected.class), List.of()));
        assertTrue(ex.getMessage().contains("OversizedFieldDescriptionIsRejected.remark"));
        assertTrue(ex.getMessage().contains("513"));
        assertTrue(ex.getMessage().contains("512"));
    }

    // ------- OPTION / MULTI_OPTION are forward-inferred only ------------
    // OPTION / MULTI_OPTION can never be written explicitly in
    // @Field(fieldType = ...). They are always derived from the Java type
    // (enum → OPTION, List<enum> → MULTI_OPTION). Writing them explicitly is
    // rejected — even when the Java type matches.

    @Model
    @SuppressWarnings("unused")
    static class ExplicitOptionOnEnumIsRejected extends AuditableModel {
        @Field(fieldType = FieldType.OPTION)  // redundant; inference would produce OPTION anyway
        private Tier tier;
        @Override public Serializable getId() { return null; }
    }

    @Test
    void explicitOptionFieldType_isAlwaysRejected_evenOnEnumType() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(ExplicitOptionOnEnumIsRejected.class), List.of()));
        assertTrue(ex.getMessage().contains("OPTION"));
        assertTrue(ex.getMessage().contains("tier"));
        assertTrue(ex.getMessage().contains("auto-derived"));
    }

    @Model
    @SuppressWarnings("unused")
    static class ExplicitOptionOnStringIsRejected extends AuditableModel {
        @Field(fieldType = FieldType.OPTION)
        private String tierCode;
        @Override public Serializable getId() { return null; }
    }

    @Test
    void explicitOptionFieldType_isAlwaysRejected_onNonEnumType() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(ExplicitOptionOnStringIsRejected.class), List.of()));
        assertTrue(ex.getMessage().contains("OPTION"));
        assertTrue(ex.getMessage().contains("tierCode"));
    }

    @Model
    @SuppressWarnings("unused")
    static class ExplicitMultiOptionIsRejected extends AuditableModel {
        @Field(fieldType = FieldType.MULTI_OPTION)
        private List<Tier> tiers;
        @Override public Serializable getId() { return null; }
    }

    @Test
    void explicitMultiOptionFieldType_isAlwaysRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(ExplicitMultiOptionIsRejected.class), List.of()));
        assertTrue(ex.getMessage().contains("MULTI_OPTION"));
        assertTrue(ex.getMessage().contains("tiers"));
    }

    // ------- autoSequence: mapped through, STRING fields only -------------

    @Model
    @SuppressWarnings("unused")
    static class AutoSequenceOnStringIsAccepted extends AuditableModel {
        @Field(autoSequence = true, length = 32)
        private String code;
        @Field(length = 128)
        private String name;
        @Override public Serializable getId() { return null; }
    }

    @Test
    void autoSequence_onStringField_isMappedThrough() {
        AnnotationScanResult result =
                parser.parse(List.of(AutoSequenceOnStringIsAccepted.class), List.of());
        assertEquals(Boolean.TRUE, byFieldName(result.fields(), "code").getAutoSequence());
        // Un-flagged fields carry an explicit false (not null) so the diff against a DB row's 0/false never reports a phantom modification.
        assertEquals(Boolean.FALSE, byFieldName(result.fields(), "name").getAutoSequence());
    }

    @Model
    @SuppressWarnings("unused")
    static class AutoSequenceOnLongIsRejected extends AuditableModel {
        @Field(autoSequence = true)
        private Long counter;
        @Override public Serializable getId() { return null; }
    }

    @Test
    void autoSequence_onNonStringField_isRejectedAtParse() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(AutoSequenceOnLongIsRejected.class), List.of()));
        assertTrue(ex.getMessage().contains("AutoSequenceOnLongIsRejected.counter"));
        assertTrue(ex.getMessage().contains("autoSequence = true"));
        assertTrue(ex.getMessage().contains("STRING"));
    }

    @Model
    @SuppressWarnings("unused")
    static class AutoSequenceOnDynamicIsRejected extends AuditableModel {
        @Field(autoSequence = true, dynamic = true, length = 32)
        private String code;
        @Override public Serializable getId() { return null; }
    }

    @Test
    void autoSequence_onDynamicField_isRejectedAtParse() {
        // A dynamic field is never stored — the allocated number would be silently lost.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(AutoSequenceOnDynamicIsRejected.class), List.of()));
        assertTrue(ex.getMessage().contains("AutoSequenceOnDynamicIsRejected.code"));
        assertTrue(ex.getMessage().contains("dynamic"));
    }

    @Model
    @SuppressWarnings("unused")
    static class AutoSequenceOnComputedIsRejected extends AuditableModel {
        @Field(autoSequence = true, computed = true, expression = "a + b", length = 32)
        private String code;
        @Override public Serializable getId() { return null; }
    }

    @Test
    void autoSequence_onComputedField_isRejectedAtParse() {
        // The compute processor runs after the sequence fill and overwrites the value.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(AutoSequenceOnComputedIsRejected.class), List.of()));
        assertTrue(ex.getMessage().contains("AutoSequenceOnComputedIsRejected.code"));
        assertTrue(ex.getMessage().contains("computed"));
    }

    @Model(idStrategy = IdStrategy.EXTERNAL_ID)
    @SuppressWarnings("unused")
    static class AutoSequenceOnIdIsRejected extends AuditableModel {
        @Field(autoSequence = true, length = 64)
        private String id;
        @Override public Serializable getId() { return null; }
    }

    @Test
    void autoSequence_onIdField_isRejectedAtParse() {
        // Primary-key generation is governed by @Model(idStrategy); the flag would be
        // silently dropped by buildIdField otherwise.
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(AutoSequenceOnIdIsRejected.class), List.of()));
        assertTrue(ex.getMessage().contains("AutoSequenceOnIdIsRejected"));
        assertTrue(ex.getMessage().contains("idStrategy"));
    }

    // The forward-inference path (the only valid way to get OPTION/MULTI_OPTION)
    // is already covered by `fieldType_inferred_fromJavaType_includingEnumOptionSetCode`
    // above — when Java type is enum, parser sets OPTION + auto optionSetCode.

    // ------- explicit relational fieldType still derives relatedModel ---

    @Model
    @SuppressWarnings("unused")
    static class ExplicitRelationDerivesRelatedModel extends AuditableModel {
        @Field
        private Long id;

        // explicit *_TO_ONE on an entity-typed field → relatedModel derived from the type
        @Field(fieldType = FieldType.MANY_TO_ONE)
        private Customer customer;

        // explicit *_TO_MANY on List<entity> → relatedModel derived from the element type
        @Field(fieldType = FieldType.ONE_TO_MANY, relatedField = "customerId")
        private List<Customer> customers;

        // raw FK id (Long) carries no entity → explicit relatedModel is required and used as-is
        @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = Customer.class)
        private Long ownerId;

        @Override
        public Serializable getId() {
            return id;
        }
    }

    @Test
    void explicitRelationalFieldType_derivesRelatedModel_fromJavaType() {
        AnnotationScanResult result =
                parser.parse(List.of(ExplicitRelationDerivesRelatedModel.class), List.of());

        // entity-typed field: relatedModel derived even though fieldType is explicit
        SysField customer = byFieldName(result.fields(), "customer");
        assertEquals(FieldType.MANY_TO_ONE, customer.getFieldType());
        assertEquals("Customer", customer.getRelatedModel());

        // List<entity> field: relatedModel derived from the element type
        SysField customers = byFieldName(result.fields(), "customers");
        assertEquals(FieldType.ONE_TO_MANY, customers.getFieldType());
        assertEquals("Customer", customers.getRelatedModel());

        // Long FK id: not derivable from the type → explicit relatedModel is used as-is
        SysField ownerId = byFieldName(result.fields(), "ownerId");
        assertEquals(FieldType.MANY_TO_ONE, ownerId.getFieldType());
        assertEquals("Customer", ownerId.getRelatedModel());
    }

    // ------- reference-by-code FK: Java type must match referenced column ----
    // A scalar TO_ONE FK stores the referenced column's value verbatim and the
    // FK column mirrors that column physically. The FK's Java type must therefore
    // resolve to the same FieldType as the referenced field — caught at parse time,
    // since the Java type is discarded thereafter and the mismatch would otherwise
    // surface only as a runtime cast failure.

    @Model
    @SuppressWarnings("unused")
    static class RefByCodeTarget extends AuditableModel {
        @Field private Long id;
        @Field private String code;   // String business key
        @Override public Serializable getId() { return id; }
    }

    @Model
    @SuppressWarnings("unused")
    static class StringPkTarget extends AuditableModel {
        @Field private String id;     // String primary key
        @Override public Serializable getId() { return id; }
    }

    @Model
    @SuppressWarnings("unused")
    static class CodeFkTypeMatches extends AuditableModel {
        @Field private Long id;
        @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = RefByCodeTarget.class,
                relatedField = "code")
        private String targetCode;    // String FK → String code: OK
        @Override public Serializable getId() { return id; }
    }

    @Model
    @SuppressWarnings("unused")
    static class CodeFkTypeMismatch extends AuditableModel {
        @Field private Long id;
        @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = RefByCodeTarget.class,
                relatedField = "code")
        private Long targetCode;      // Long FK → String code: MISMATCH
        @Override public Serializable getId() { return id; }
    }

    @Model
    @SuppressWarnings("unused")
    static class IdFkOntoStringPkMismatch extends AuditableModel {
        @Field private Long id;
        @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = StringPkTarget.class)
        private Long targetRef;       // Long FK → String id (default relatedField=id): MISMATCH
        @Override public Serializable getId() { return id; }
    }

    @Test
    void referenceByCodeFk_matchingJavaType_parsesCleanly() {
        AnnotationScanResult result = parser.parse(List.of(CodeFkTypeMatches.class), List.of());
        SysField fk = byFieldName(result.fields(), "targetCode");
        assertEquals(FieldType.MANY_TO_ONE, fk.getFieldType());
        assertEquals("RefByCodeTarget", fk.getRelatedModel());
        assertEquals("code", fk.getRelatedField());
    }

    @Test
    void referenceByCodeFk_mismatchedJavaType_isRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(CodeFkTypeMismatch.class), List.of()));
        assertTrue(ex.getMessage().contains("targetCode"));
        assertTrue(ex.getMessage().contains("RefByCodeTarget.code"));
        assertTrue(ex.getMessage().contains("STRING"));
        assertTrue(ex.getMessage().contains("LONG"));
    }

    @Test
    void idFk_ontoStringPrimaryKey_withLongJavaType_isRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(IdFkOntoStringPkMismatch.class), List.of()));
        assertTrue(ex.getMessage().contains("targetRef"));
        assertTrue(ex.getMessage().contains("StringPkTarget.id"));
    }

    // ------- helpers ----------------------------------------------------

    private static SysField byFieldName(List<SysField> fields, String name) {
        return fields.stream()
                .filter(f -> name.equals(f.getFieldName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + name));
    }

    private static SysOptionItem byItemCode(List<SysOptionItem> items, String code) {
        return items.stream()
                .filter(i -> code.equals(i.getItemCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no item " + code));
    }

    // ------- @Index parsing ---------------------------------------------

    @Index(fields = {"name"})
    @Index(indexName = "uk_indexed_code", fields = {"code"}, unique = true)
    @Model(tableName = "indexed_entity")
    @SuppressWarnings("unused")
    static class IndexedEntity extends AuditableModel {
        @Field private Long id;
        @Field(length = 100) private String name;
        @Field(length = 32) private String code;
        @Override public Serializable getId() { return id; }
    }

    @Test
    void parsesRepeatedIndexes_intoSysModelIndexRows() {
        AnnotationScanResult result = parser.parse(List.of(IndexedEntity.class), List.of());
        assertEquals(2, result.modelIndexes().size());
    }

    @Test
    void index_withoutName_derivesIdxOrUkPrefix() {
        AnnotationScanResult result = parser.parse(List.of(IndexedEntity.class), List.of());
        SysModelIndex byName = byIndexName(result.modelIndexes(), "idx_indexed_entity_name");
        assertEquals(false, byName.getUniqueIndex());
        assertEquals(List.of("name"), byName.getIndexFields());
    }

    @Test
    void index_withExplicitName_keepsName() {
        AnnotationScanResult result = parser.parse(List.of(IndexedEntity.class), List.of());
        SysModelIndex uk = byIndexName(result.modelIndexes(), "uk_indexed_code");
        assertEquals(true, uk.getUniqueIndex());
        assertEquals(List.of("code"), uk.getIndexFields());
    }

    @Test
    void index_referencingUnknownField_throws() {
        @Index(fields = {"nonexistent"})
        @Model
        @SuppressWarnings("unused")
        class BadIndex extends AuditableModel {
            @Field private Long id;
            @Override public Serializable getId() { return id; }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(BadIndex.class), List.of()));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    /** A compile-time-constant message longer than the sys_model_index.message width (256). */
    private static final String MSG_OVER_256 =
              "01234567890123456789012345678901234567890123456789"
            + "01234567890123456789012345678901234567890123456789"
            + "01234567890123456789012345678901234567890123456789"
            + "01234567890123456789012345678901234567890123456789"
            + "01234567890123456789012345678901234567890123456789"
            + "01234567890123456789012345678901234567890123456789";

    @Test
    void index_messageOnNonUniqueIndex_throws() {
        @Index(fields = {"code"}, message = "dup")
        @Model
        @SuppressWarnings("unused")
        class NonUniqueMsg extends AuditableModel {
            @Field private Long id;
            @Field(length = 32) private String code;
            @Override public Serializable getId() { return id; }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(NonUniqueMsg.class), List.of()));
        assertTrue(ex.getMessage().contains("not unique"));
    }

    @Test
    void index_messageOnUniqueIndex_isStored() {
        @Index(fields = {"code"}, unique = true, message = "Code already used.")
        @Model(tableName = "unique_msg_entity")
        @SuppressWarnings("unused")
        class UniqueMsg extends AuditableModel {
            @Field private Long id;
            @Field(length = 32) private String code;
            @Override public Serializable getId() { return id; }
        }
        AnnotationScanResult result = parser.parse(List.of(UniqueMsg.class), List.of());
        assertEquals("Code already used.", result.modelIndexes().getFirst().getMessage());
    }

    @Test
    void index_messageExceedingWidth_throws() {
        @Index(fields = {"code"}, unique = true, message = MSG_OVER_256)
        @Model
        @SuppressWarnings("unused")
        class LongMsg extends AuditableModel {
            @Field private Long id;
            @Field(length = 32) private String code;
            @Override public Serializable getId() { return id; }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(LongMsg.class), List.of()));
        assertTrue(ex.getMessage().contains("message exceeds"));
    }

    @Test
    void index_explicitNameExceeding60_throws() {
        @Index(
                indexName = "uk_this_is_a_very_long_explicit_index_name_way_over_the_sixty_char_limit",
                fields = {"code"}, unique = true)
        @Model
        @SuppressWarnings("unused")
        class LongName extends AuditableModel {
            @Field private Long id;
            @Field(length = 32) private String code;
            @Override public Serializable getId() { return id; }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(LongName.class), List.of()));
        assertTrue(ex.getMessage().contains("exceeds") && ex.getMessage().contains("60"));
    }

    @Test
    void index_derivedNameExceeding60_throws() {
        @Index(fields = {"code"})
        @Model(tableName = "very_long_physical_table_name_for_index_derivation_test")
        @SuppressWarnings("unused")
        class LongDerived extends AuditableModel {
            @Field private Long id;
            @Field(length = 32) private String code;
            @Override public Serializable getId() { return id; }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(LongDerived.class), List.of()));
        assertTrue(ex.getMessage().contains("declare a shorter explicit indexName"));
    }

    @Test
    void modelWithoutAnyIndex_yieldsEmptyIndexList() {
        AnnotationScanResult result = parser.parse(List.of(Customer.class), List.of());
        assertEquals(0, result.modelIndexes().size());
    }

    // ------- silent-collapse fail-fast guards -----------------------------

    /** Holders give the two fixtures the SAME simple name in different "packages". */
    @SuppressWarnings("unused")
    static class HolderA {
        @Model
        static class Product {
            @Field private Long id;
        }
    }

    @SuppressWarnings("unused")
    static class HolderB {
        @Model
        static class Product {
            @Field private Long id;
        }
    }

    @Test
    void duplicateModelSimpleName_throws() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(HolderA.Product.class, HolderB.Product.class), List.of()));
        assertTrue(ex.getMessage().contains("Duplicate modelName 'Product'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("HolderA") && ex.getMessage().contains("HolderB"),
                "must name both declarations: " + ex.getMessage());
    }

    @OptionSet
    @SuppressWarnings("unused")
    enum DupItemCode {
        FIRST("x"),
        SECOND("x");   // same @JsonValue → same itemCode

        @JsonValue
        private final String code;

        DupItemCode(String code) {
            this.code = code;
        }
    }

    @Test
    void duplicateItemCode_throws() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(), List.of(DupItemCode.class)));
        assertTrue(ex.getMessage().contains("Duplicate itemCode 'x'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("FIRST") && ex.getMessage().contains("SECOND"),
                ex.getMessage());
    }

    @Test
    void duplicateIndexName_onOneModel_throws() {
        @Index(indexName = "idx_same", fields = {"code"})
        @Index(indexName = "idx_same", fields = {"name"})
        @Model
        @SuppressWarnings("unused")
        class DupIndexName extends AuditableModel {
            @Field private Long id;
            @Field(length = 32) private String code;
            @Field(length = 64) private String name;
            @Override public Serializable getId() { return id; }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(DupIndexName.class), List.of()));
        assertTrue(ex.getMessage().contains("'idx_same' twice"), ex.getMessage());
    }

    @Test
    void index_onDynamicField_throws() {
        @Index(fields = {"computedFlag"})
        @Model
        @SuppressWarnings("unused")
        class DynamicIndexed extends AuditableModel {
            @Field private Long id;
            @Field(dynamic = true, computed = true, expression = "1 == 1")
            private Boolean computedFlag;
            @Override public Serializable getId() { return id; }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(DynamicIndexed.class), List.of()));
        assertTrue(ex.getMessage().contains("no physical column"), ex.getMessage());
    }

    @Test
    void reservedWordColumnName_throws() {
        @Model
        @SuppressWarnings("unused")
        class ReservedColumn extends AuditableModel {
            @Field private Long id;
            @Field private String order;   // snake_case("order") = "order" — reserved
            @Override public Serializable getId() { return id; }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(ReservedColumn.class), List.of()));
        assertTrue(ex.getMessage().contains("reserved SQL keyword"), ex.getMessage());
        assertTrue(ex.getMessage().contains("order"), ex.getMessage());
    }

    @Test
    void invalidExplicitColumnName_throws() {
        @Model
        @SuppressWarnings("unused")
        class InvalidColumn extends AuditableModel {
            @Field private Long id;
            @Field(columnName = "customer name") private String name;
            @Override public Serializable getId() { return id; }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(InvalidColumn.class), List.of()));
        assertTrue(ex.getMessage().contains("not a valid SQL identifier"), ex.getMessage());
        assertTrue(ex.getMessage().contains("customer name"), ex.getMessage());
    }

    @Test
    void reservedWordTableName_throws() {
        @Model(tableName = "order")
        @SuppressWarnings("unused")
        class ReservedTable extends AuditableModel {
            @Field private Long id;
            @Override public Serializable getId() { return id; }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(ReservedTable.class), List.of()));
        assertTrue(ex.getMessage().contains("reserved SQL keyword"), ex.getMessage());
    }

    @Test
    void invalidExplicitTableName_throws() {
        @Model(tableName = "bad-table")
        @SuppressWarnings("unused")
        class InvalidTable extends AuditableModel {
            @Field private Long id;
            @Override public Serializable getId() { return id; }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(InvalidTable.class), List.of()));
        assertTrue(ex.getMessage().contains("not a valid SQL identifier"), ex.getMessage());
        assertTrue(ex.getMessage().contains("bad-table"), ex.getMessage());
    }

    @Test
    void invalidExplicitIndexName_throws() {
        @Index(indexName = "idx_bad-name", fields = {"code"})
        @Model
        @SuppressWarnings("unused")
        class InvalidIndex extends AuditableModel {
            @Field private Long id;
            @Field(length = 32) private String code;
            @Override public Serializable getId() { return id; }
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.parse(List.of(InvalidIndex.class), List.of()));
        assertTrue(ex.getMessage().contains("not a valid SQL identifier"), ex.getMessage());
        assertTrue(ex.getMessage().contains("idx_bad-name"), ex.getMessage());
    }

    private static SysModelIndex byIndexName(
            List<SysModelIndex> indexes, String name) {
        return indexes.stream()
                .filter(i -> name.equals(i.getIndexName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no index " + name));
    }
}
