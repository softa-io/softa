package io.softa.starter.flow.runtime.engine;

import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.dto.FlowExpressionPreview;
import io.softa.starter.flow.dto.FlowExpressionPreviewRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The designer tries an expression or a template over sample values, read the way a flow reads it. */
class FlowExpressionPreviewerTest {

    @Test
    void anExpressionGivesItsValueAndType() {
        FlowExpressionPreview preview = preview("amount > 100 && type == 'Annual'", Map.of("amount", 150, "type", "Annual"), false);

        assertTrue(preview.valid());
        assertEquals(Boolean.TRUE, preview.result());
        assertEquals("Boolean", preview.resultType());
    }

    @Test
    void anExpressionWrappedWholeInBracesIsTheExpression() {
        assertEquals(Boolean.TRUE, preview("{{ amount > 100 }}", Map.of("amount", 150), false).result());
    }

    @Test
    void aTemplateIsInterpolatedAndOnePlaceholderGivesItsValue() {
        assertEquals("Claim C-1 for 150", preview("Claim {{ claimNo }} for {{ amount }}",
                Map.of("claimNo", "C-1", "amount", 150), true).result());
        FlowExpressionPreview whole = preview("{{ amount * 2 }}", Map.of("amount", 150), true);
        assertEquals(300, ((Number) whole.result()).intValue());
    }

    @Test
    void whatDoesNotRunSaysWhy() {
        FlowExpressionPreview preview = preview("amount +", Map.of(), false);

        assertFalse(preview.valid());
        assertNotNull(preview.message());
    }

    private static FlowExpressionPreview preview(String expression, Map<String, Object> variables, boolean template) {
        return FlowExpressionPreviewer.preview(new FlowExpressionPreviewRequest(expression, variables, template));
    }
}
