package io.softa.starter.flow.design;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import io.softa.framework.base.config.JacksonConfig;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.flow.enums.ApproverDeptScope;
import io.softa.starter.flow.enums.ApproverRelativeTo;
import io.softa.starter.flow.enums.ApproverSourceType;
import io.softa.starter.flow.runtime.bundle.NodeConfigParser;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * A flow design as databases hold it — {@code flow_design.design_json}, {@code flow_bundle.design_json} — still reads
 * after an enum changes. A read throws on a code the enum no longer has (the runtime mapper deliberately does not read
 * unknown enum values as null), so renaming or removing a stored code leaves every flow holding it unable to load,
 * publish or reapply: when {@code FlowScenario} Process was split into Approval / Automation, the flows stored as
 * Process stopped reading until a hand-written SQL rewrote them.
 * <p>
 * <b>A failing fixture means the change needs a migration SQL that rewrites the stored rows, shipped with it — never
 * edit the fixture to match the code.</b> Add a fixture when a new shape reaches a database.
 * {@code flow_bundle.compiled_json} repeats these codes and adds its own (each node's {@code errorConfig.strategy});
 * it has no fixture here.
 */
class StoredDesignJsonTest {

    /** Reads as the runtime JsonMapper does: Jackson 3 defaults plus the application's deserialization policy. */
    private static final JsonMapper RUNTIME = runtimeMapper();

    /**
     * Each fixture is the {@code designJson} of a zingkey-hcm seed some database has loaded:
     * {@code expense-claim-2026-10-27} is 9663e026's with {@code scenario} rewritten Process → Approval, as the
     * hand-written SQL did — its {@code allowInitiatorWithdraw} is no longer a property and reads as absent
     * (Release-20261030's {@code 01-before-jar-withdraw-policy.sql} moves it to {@code withdrawPolicy}); the two of
     * 2026-10-31 are 58127bc7's.
     */
    @ParameterizedTest
    @ValueSource(strings = {"expense-claim-2026-10-27.json", "confirmation-2026-10-31.json", "pay-run-2026-10-31.json"})
    void aStoredDesignStillReads(String fixture) throws IOException {
        String json = read(fixture);
        assertDoesNotThrow(() -> readAsTheRuntimeDoes(json), fixture + " no longer reads: an enum code it stores was "
                + "renamed or removed. Ship a migration SQL that rewrites the stored rows; do not edit the fixture.");
    }

    /** What the guard rests on: a code the enum no longer has fails the read instead of reading as null. */
    @Test
    void aRemovedCodeDoesNotRead() {
        assertThrows(JacksonException.class,
                () -> RUNTIME.readValue("{\"scenario\": \"Process\"}", DesignFlowDefinition.class));
    }

    private static void readAsTheRuntimeDoes(String json) {
        DesignFlowDefinition design = RUNTIME.readValue(json, DesignFlowDefinition.class);
        // A node's config is typed when the design compiles and when its bundle loads, through JsonUtils — outside
        // Spring a bare mapper, so hand it the runtime's.
        try (MockedStatic<JsonUtils> jsonUtils = mockStatic(JsonUtils.class, CALLS_REAL_METHODS)) {
            jsonUtils.when(JsonUtils::getMapper).thenReturn(RUNTIME);
            for (FlowGraphNode node : design.getGraph().getNodes()) {
                if (NodeConfigParser.parse(node.getType(), node.getConfig()) instanceof ApprovalNodeConfig approval) {
                    readSource(approval.getApproverSource());
                }
            }
        }
        if (design.getCc() != null) {
            design.getCc().forEach(rule -> readSource(rule.getRecipientSource()));
        }
    }

    /** An approver source stays an untyped map; its codes are read where approvers are validated and resolved. */
    private static void readSource(Map<String, Object> source) {
        if (source == null) {
            return;
        }
        if (source.get("type") != null) {
            ApproverSourceType.of(source.get("type").toString());
        }
        ApproverRelativeTo.of(source.get("relativeTo"), ApproverRelativeTo.SUBJECT);
        ApproverDeptScope.of(source.get("deptScope"), ApproverDeptScope.ALL);
    }

    private static JsonMapper runtimeMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonConfig().globalJacksonPolicy().customize(builder);
        return builder.build();
    }

    private static String read(String fixture) throws IOException {
        try (InputStream in = StoredDesignJsonTest.class.getResourceAsStream("/stored-designs/" + fixture)) {
            assertNotNull(in, "missing fixture " + fixture);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
