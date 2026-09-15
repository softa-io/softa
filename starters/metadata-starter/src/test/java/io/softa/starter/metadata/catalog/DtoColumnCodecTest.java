package io.softa.starter.metadata.catalog;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.framework.orm.meta.FieldConstraints;
import io.softa.starter.metadata.checksum.CanonicalMetadataSerializer;
import io.softa.starter.metadata.entity.SysField;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The DTO column on its way to the database, back, into the diff and into the checksum. One JSON
 * text per logical declaration is what makes the catalog's change detection and the cross-lane
 * checksum indifferent to how the declaration was spelled.
 */
class DtoColumnCodecTest {

    private static final FieldConstraints DECLARED = FieldConstraints.of(
            "0", null, null, "Headcount cannot be negative.", null, null, null, null, "Department.activeEmpCount");

    @Test
    void sysFieldConstraintsColumnGetsTheDtoCodec() {
        SysCatalog.SysTable<SysField> table = SysCatalog.of(SysField.class);
        SysColumn<SysField> column = table.data().stream()
                .filter(c -> "constraints".equals(c.name())).findFirst().orElseThrow();
        assertEquals("constraints", column.column());
        SysField field = new SysField();
        field.setConstraints(DECLARED);
        assertEquals("{\"message\":\"Headcount cannot be negative.\",\"min\":\"0\"}", column.toDb(field));
        assertNull(column.toDb(new SysField()));
    }

    @Test
    void canonicalTextSortsKeysDropsNullsAndStoresNothingAsNull() {
        Codec codec = Codecs.dto(FieldConstraints.class);
        assertEquals("{\"message\":\"Headcount cannot be negative.\",\"min\":\"0\"}", codec.toDb(DECLARED));
        assertNull(codec.toDb(null));
        // a condition keeps its array order — order is semantic in a filter tree
        FieldConstraints conditional = FieldConstraints.of(null, null, null, null,
                "[[\"reason\", \"=\", \"Others\"], [\"@mode\", \"=\", \"update\"]]", null, null, null, "M.f");
        assertEquals("{\"requiredWhen\":[[\"reason\",\"=\",\"Others\"],\"AND\",[\"@mode\",\"=\",\"update\"]]}", codec.toDb(conditional));
        FieldConstraints always = FieldConstraints.of(null, null, null, null, "true", null, null, null, "M.f");
        assertEquals("{\"requiredWhen\":true}", codec.toDb(always));
    }

    @Test
    void readsBackIntoTheDeclaredClassAndComparesByMeaning() throws Exception {
        Codec codec = Codecs.dto(FieldConstraints.class);
        ResultSet rs = Mockito.mock(ResultSet.class);
        // hand-written spelling: different key order, whitespace, an explicit null
        Mockito.when(rs.getString("constraints"))
                .thenReturn("{ \"min\" : \"0\", \"max\": null, \"message\": \"Headcount cannot be negative.\" }");
        Object loaded = codec.fromDb(rs, "constraints");
        assertEquals(DECLARED, loaded);
        assertTrue(codec.typedEquals(DECLARED, loaded));
        assertTrue(codec.typedEquals(null, null));
        assertFalse(codec.typedEquals(DECLARED, null));

        Mockito.when(rs.getString("constraints")).thenReturn("   ");
        assertNull(codec.fromDb(rs, "constraints"));
    }

    @Test
    void checksumHashesTheCanonicalJsonNotTheRecordToString() {
        String canonical = CanonicalMetadataSerializer.canonical(Map.of("constraints", DECLARED), List.of("constraints"));
        assertEquals("constraints=j:{\"message\":\"Headcount cannot be negative.\",\"min\":\"0\"};", canonical);
        // absent and null hash the same, as for every other attribute
        assertEquals(CanonicalMetadataSerializer.canonical(Map.of(), List.of("constraints")),
                CanonicalMetadataSerializer.canonical(java.util.Collections.singletonMap("constraints", null), List.of("constraints")));
    }

    @Test
    void aRowReadThroughTheModelServiceCarriesTheColumnAsJsonNodeAndHashesTheSame() {
        // both checksum lanes read their rows via searchList, whose JSON processor parses the text into a
        // JsonNode — a differently spelled or null-carrying text must still hash as the same declaration
        Object node = io.softa.framework.base.utils.JsonUtils.stringToObject(
                "{\"min\":\"0\",\"message\":\"Headcount cannot be negative.\",\"max\":null}", tools.jackson.databind.JsonNode.class);
        assertEquals(CanonicalMetadataSerializer.canonical(Map.of("constraints", DECLARED), List.of("constraints")),
                CanonicalMetadataSerializer.canonical(Map.of("constraints", node), List.of("constraints")));
        Object empty = io.softa.framework.base.utils.JsonUtils.stringToObject("{}", tools.jackson.databind.JsonNode.class);
        assertEquals(CanonicalMetadataSerializer.canonical(Map.of(), List.of("constraints")),
                CanonicalMetadataSerializer.canonical(Map.of("constraints", empty), List.of("constraints")));
    }
}
