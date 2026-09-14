package io.softa.starter.user.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import io.softa.starter.user.enums.AccountStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The company picker is driven entirely by what reaches the client, so the wire shape is the
 * contract — not the Java accessor.
 *
 * <p>{@code selectable()} is neither a record component nor a {@code getX}/{@code isX} getter, so
 * Jackson does not discover it on its own. Without an explicit mapping the field is absent, every
 * option reads as unselectable in the browser, and the picker refuses every company it just
 * listed — with no error anywhere, because nothing failed.
 */
class MembershipOptionJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void selectableReachesTheClient() throws Exception {
        String json = mapper.writeValueAsString(
                new MembershipOption(1L, 2L, "Acme", AccountStatus.ACTIVE, false, false, null));

        assertThat(json).contains("\"selectable\":true");
    }

    @Test
    void aNonActiveOptionSaysSoOnTheWire() throws Exception {
        String json = mapper.writeValueAsString(
                new MembershipOption(1L, 2L, "Acme", AccountStatus.FROZEN, false, false, null));

        assertThat(json).contains("\"selectable\":false");
    }

    @Test
    void lockedIsCarried_andLeavesSelectableAlone() throws Exception {
        // The badge reaches the client; the picker's decision does not change with it, because a
        // person who just got in with a code (allowed during a lock, PRD D5) must still be able to
        // enter their company.
        String json = mapper.writeValueAsString(
                new MembershipOption(1L, 2L, "Acme", AccountStatus.ACTIVE, true, false, null));

        assertThat(json).contains("\"locked\":true").contains("\"selectable\":true");
    }

    @Test
    void theOtherFieldsAreCarriedToo() throws Exception {
        String json = mapper.writeValueAsString(
                new MembershipOption(1L, 2L, "Acme", AccountStatus.ACTIVE, false, false, null));

        assertThat(json).contains("\"accountId\":1")
                .contains("\"tenantId\":2")
                .contains("\"tenantName\":\"Acme\"")
                .contains("\"status\":");
    }
}
