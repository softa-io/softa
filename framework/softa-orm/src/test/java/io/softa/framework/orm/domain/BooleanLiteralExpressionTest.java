package io.softa.framework.orm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A boolean right-hand side, in both spellings.
 *
 * <p>It used to work only in the JSON form: {@code BOOLEAN} sat below {@code FIELD} in the lexer, both
 * match {@code true} at the same length, and ANTLR breaks that tie by declaration order — so the
 * literal lexed as a field name and the unit never reached a value. Harmless while a bare name was not
 * a legal value (it was a syntax error), and not harmless at all once one is, which is why the order
 * had to be fixed in the same change.
 */
class BooleanLiteralExpressionTest {

    @Test
    void bothSpellingsAcceptABooleanRightHandSide() {
        assertThat(Filters.of("hasProbation != true"))
                .isEqualTo(Filters.of("[[\"hasProbation\", \"!=\", true]]"));
        assertThat(Filters.of("policyEnableEntitlement = false"))
                .isEqualTo(Filters.of("[[\"policyEnableEntitlement\", \"=\", false]]"));
    }

    @Test
    void aBooleanIsTheLiteralAndNotAFieldNamedTrue() {
        // the whole point of the lexer order: `true` must not read as "the field named true"
        assertThat(Filters.of("hasProbation != true").getFilterUnit().getValue()).isEqualTo(true);
    }
}
