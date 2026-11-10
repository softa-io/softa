package io.softa.starter.flow.runtime.bundle;

import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.TaskNodeConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A task node's input, however its config holds it: the designer writes the executor's fields at the top, as the
 * executor's descriptor lists them; a hand-written design may nest them under {@code input}; either may sit inside
 * {@code task}. Read only from {@code input}, a designed node ran with none.
 */
class NodeConfigParserTaskInputTest {

    @Test
    void theDesignersFieldsAreTheInputAndTheNodesOwnKeysStayItsOwn() {
        TaskNodeConfig config = parse(Map.of(
                "url", "https://example.com/hook",
                "method", "POST",
                "outputVariable", "response",
                "options", Map.of("timeoutSeconds", 5)));

        assertThat(config.getInput()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "url", "https://example.com/hook",
                "method", "POST"));
        assertThat(config.getOutputVariable()).isEqualTo("response");
        assertThat(config.getOptions()).containsEntry("timeoutSeconds", 5);
    }

    @Test
    void aNestedInputIsReadAsItWas() {
        TaskNodeConfig config = parse(Map.of(
                "input", Map.of("url", "https://example.com/hook"),
                "outputVariable", "response"));

        assertThat(config.getInput()).containsExactlyEntriesOf(Map.of("url", "https://example.com/hook"));
        assertThat(config.getOutputVariable()).isEqualTo("response");
    }

    @Test
    void aConfigWrappedInTaskIsReadTheSameWay() {
        assertThat(parse(Map.of("task", Map.of("url", "https://example.com/hook"))).getInput())
                .containsExactlyEntriesOf(Map.of("url", "https://example.com/hook"));
        assertThat(parse(Map.of("task", Map.of("input", Map.of("url", "https://example.com/hook")))).getInput())
                .containsExactlyEntriesOf(Map.of("url", "https://example.com/hook"));
    }

    @Test
    void aConfigOfOnlyTheNodesOwnKeysHasNoInput() {
        assertThat(parse(Map.of("outputVariable", "response")).getInput()).isNull();
    }

    private static TaskNodeConfig parse(Map<String, Object> config) {
        return (TaskNodeConfig) NodeConfigParser.parse(FlowNodeType.CALL_WEBHOOK, config);
    }
}
