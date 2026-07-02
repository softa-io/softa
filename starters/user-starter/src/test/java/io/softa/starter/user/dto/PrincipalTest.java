package io.softa.starter.user.dto;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrincipalTest {

    /** Domain DTO used as a stand-in for an extension slot's typed value. */
    static class Fixture {
        public Long id;
        public String label;
        public Fixture() {}
        public Fixture(Long id, String label) { this.id = id; this.label = label; }
    }

    @Test
    void getExtension_alreadyTyped_returnsSameInstance() {
        Fixture original = new Fixture(1L, "hello");
        Principal p = Principal.builder().userId(1L).build();
        p.putExtension("fixture", original);

        Fixture got = p.getExtension("fixture", Fixture.class);

        assertThat(got).isSameAs(original);
    }

    @Test
    void getExtension_linkedHashMapValue_convertsViaJackson() {
        // Simulate a Redis cache-hit shape: Jackson defaults untyped Object
        // slots to LinkedHashMap on deserialization. The typed accessor must
        // rehydrate via convertValue.
        Principal p = Principal.builder().userId(1L).build();
        LinkedHashMap<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("id", 42);
        shaped.put("label", "converted");
        p.putExtension("fixture", shaped);

        Fixture got = p.getExtension("fixture", Fixture.class);

        assertThat(got).isNotNull();
        assertThat(got.id).isEqualTo(42L);
        assertThat(got.label).isEqualTo("converted");
    }

    @Test
    void getExtension_afterConversion_cachesTypedInstance() {
        Principal p = Principal.builder().userId(1L).build();
        LinkedHashMap<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("id", 7);
        shaped.put("label", "cached");
        p.putExtension("fixture", shaped);

        Fixture first = p.getExtension("fixture", Fixture.class);
        Fixture second = p.getExtension("fixture", Fixture.class);

        assertThat(second).isSameAs(first);
    }

    @Test
    void getExtension_missingSlot_returnsNull() {
        Principal p = Principal.builder().userId(1L).build();
        assertThat(p.getExtension("absent", Fixture.class)).isNull();
    }

    @Test
    void getExtension_nullType_returnsNull() {
        Principal p = Principal.builder().userId(1L).build();
        p.putExtension("fixture", new Fixture(1L, "x"));
        Fixture got = p.getExtension("fixture", null);
        assertThat(got).isNull();
    }

    @Test
    void putExtension_lazilyAllocatesMap() {
        Principal p = new Principal();
        p.setExtensions(null);
        p.putExtension("k", "v");
        assertThat(p.getExtensions())
                .isNotNull()
                .containsEntry("k", "v");
    }

    @Test
    void getExtension_defaultBuilderMapIsEmpty() {
        Principal p = Principal.builder().userId(1L).build();
        assertThat(p.getExtensions()).isEmpty();
    }

    @Test
    void getExtension_reusesExistingMap() {
        Map<String, Object> existing = new HashMap<>();
        existing.put("k1", "v1");
        Principal p = Principal.builder().userId(1L).extensions(existing).build();
        p.putExtension("k2", "v2");
        assertThat(existing).containsEntry("k2", "v2");
    }
}
