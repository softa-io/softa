package io.softa.starter.studio.checksum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.metadata.checksum.AggregateChecksumService;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.entity.DesignModel;
import io.softa.starter.studio.meta.entity.DesignModelIndex;

/**
 * THE load-bearing guardrail for the desired-state deploy: the runtime
 * ({@code SysModel}) and studio ({@code DesignModel}) representations of the SAME logical
 * Model aggregate MUST produce a byte-identical checksum, so the deploy can trust a
 * checksum match to mean "no change" and skip the aggregate. This test is the only place
 * that exercises both entity hierarchies through the one shared
 * {@link AggregateChecksumService}; if a future {@code @Field} diverges between the two
 * sides (name, type, or representation), this test fails rather than a deploy silently
 * shipping a no-op or skipping a real change.
 */
class CrossLaneChecksumGoldenTest {

    private final AggregateChecksumService checksum = new AggregateChecksumService();

    private static SysModel sysModel() {
        SysModel m = new SysModel();
        m.setModelName("Customer");
        m.setLabel("Customer");
        m.setTableName("customer");
        m.setBusinessKey(List.of("code"));
        m.setIdStrategy(IdStrategy.DB_AUTO_ID);
        m.setSoftDelete(Boolean.FALSE);
        m.setDescription("A customer");
        // Excluded noise — must NOT affect the hash:
        m.setId(1L);
        m.setAppCode("demo-app");
        return m;
    }

    private static SysField sysField(String name, FieldType type, Integer length) {
        SysField f = new SysField();
        f.setFieldName(name);
        f.setColumnName(name);
        f.setModelName("Customer");
        f.setFieldType(type);
        f.setLength(length);
        f.setRequired(Boolean.TRUE);
        // Non-null on BOTH sides: absent and null hash identically ('∅'), 
        // so a fixture that leaves an attr null cannot catch a one-sided attribute addition.
        f.setAutoSequence(Boolean.TRUE);
        f.setId(100L);
        return f;
    }

    private static SysModelIndex sysIndex() {
        SysModelIndex i = new SysModelIndex();
        i.setModelName("Customer");
        i.setIndexName("uk_customer_code");
        i.setIndexFields(List.of("code"));
        i.setUniqueIndex(Boolean.TRUE);
        i.setMessage("Code already used.");
        i.setId(200L);
        return i;
    }

    private static DesignModel designModel() {
        DesignModel m = new DesignModel();
        m.setModelName("Customer");
        m.setLabel("Customer");
        m.setTableName("customer");
        m.setBusinessKey(List.of("code"));
        m.setIdStrategy(IdStrategy.DB_AUTO_ID);
        m.setSoftDelete(Boolean.FALSE);
        m.setDescription("A customer");
        // Different excluded noise on the studio side — must NOT affect the hash:
        m.setId(987654321L);
        m.setAppId(42L);
        return m;
    }

    private static DesignField designField(String name, FieldType type, Integer length) {
        DesignField f = new DesignField();
        f.setFieldName(name);
        f.setColumnName(name);
        f.setModelName("Customer");
        f.setFieldType(type);
        f.setLength(length);
        f.setRequired(Boolean.TRUE);
        f.setAutoSequence(Boolean.TRUE);
        f.setId(55555L);
        f.setAppId(42L);
        f.setModelId(7L);
        return f;
    }

    private static DesignModelIndex designIndex() {
        DesignModelIndex i = new DesignModelIndex();
        i.setModelName("Customer");
        i.setIndexName("uk_customer_code");
        i.setIndexFields(List.of("code"));
        i.setUniqueIndex(Boolean.TRUE);
        i.setMessage("Code already used.");
        i.setId(66666L);
        i.setModelId(7L);
        return i;
    }

    @Test
    @DisplayName("runtime SysModel and studio DesignModel of the same logical model hash identically")
    void crossLaneModelChecksumMatches() {
        String runtime = checksum.modelChecksum(
                sysModel(),
                List.of(sysField("code", FieldType.STRING, 64), sysField("name", FieldType.STRING, 100)),
                List.of(sysIndex()));
        String studio = checksum.modelChecksum(
                designModel(),
                List.of(designField("code", FieldType.STRING, 64), designField("name", FieldType.STRING, 100)),
                List.of(designIndex()));
        assertEquals(runtime, studio,
                "design and runtime checksums diverged — a @Field name/type/representation differs across the lanes");
    }

    @Test
    @DisplayName("a real schema change diverges the cross-lane hash (test would catch drift)")
    void schemaChangeOnOneLaneDiverges() {
        String runtime = checksum.modelChecksum(
                sysModel(),
                List.of(sysField("code", FieldType.STRING, 64)),
                List.of(sysIndex()));
        // studio side widens the column — a genuine schema change must move the hash
        String studioChanged = checksum.modelChecksum(
                designModel(),
                List.of(designField("code", FieldType.STRING, 128)),
                List.of(designIndex()));
        assertNotEquals(runtime, studioChanged);
    }

    @Test
    @DisplayName("a message-only difference diverges the cross-lane hash (message participates)")
    void messageOnlyDifferenceDiverges() {
        String runtime = checksum.modelChecksum(sysModel(),
                List.of(sysField("code", FieldType.STRING, 64)),
                List.of(sysIndex()));               // message "Code already used."
        DesignModelIndex differentMessage = designIndex();
        differentMessage.setMessage("A different sentence.");
        String studio = checksum.modelChecksum(designModel(),
                List.of(designField("code", FieldType.STRING, 64)),
                List.of(differentMessage));
        assertNotEquals(runtime, studio);
    }

    @Test
    @DisplayName("child order is irrelevant across lanes")
    void childOrderIrrelevantCrossLane() {
        String runtime = checksum.modelChecksum(sysModel(),
                List.of(sysField("a", FieldType.STRING, 64), sysField("b", FieldType.INTEGER, null)),
                List.of());
        String studio = checksum.modelChecksum(designModel(),
                List.of(designField("b", FieldType.INTEGER, null), designField("a", FieldType.STRING, 64)),
                List.of());
        assertEquals(runtime, studio);
    }
}
