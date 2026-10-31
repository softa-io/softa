package io.softa.starter.flow.runtime.descriptor;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every value the designer offers for an approval node's enum settings is one its config is read with: an option
 * the backend cannot read made the whole config unreadable, and the flow could neither validate nor publish.
 */
class ApprovalNodeDescriptorValuesTest {

    private static final List<String> ENUM_FIELDS = List.of("emptyApproverStrategy", "approvalMode", "rejectMode");

    @Test
    void everyOfferedOptionAndDefaultIsReadable() {
        Map<String, Object> schema = approvalSchema();
        for (String field : ENUM_FIELDS) {
            Map<String, Object> property = property(schema, field);
            List<?> options = (List<?>) property.get("options");
            assertFalse(options.isEmpty(), field + " offers no option");
            for (Object option : options) {
                assertReadable(field, option);
            }
            assertTrue(options.contains(property.get("default")), field + " defaults to a value it does not offer");
        }
    }

    @Test
    void everyDependencyNamesAnOfferedOption() {
        Map<String, Object> schema = approvalSchema();
        for (String field : List.of("minCount", "percentage", "rejectMinCount", "rejectPercentage")) {
            Map<?, ?> depends = (Map<?, ?>) property(schema, field).get("depends");
            depends.forEach((on, value) -> assertTrue(
                    ((List<?>) property(schema, (String) on).get("options")).contains(value),
                    field + " depends on " + on + " = " + value + ", which " + on + " does not offer"));
        }
    }

    private static void assertReadable(String field, Object value) {
        ApprovalNodeConfig config = assertDoesNotThrow(
                () -> BeanTool.objectToObject(Map.of(field, value), ApprovalNodeConfig.class),
                field + " offers " + value + ", which its config cannot be read with");
        assertNotNull(config);
    }

    private static Map<String, Object> approvalSchema() {
        return new BuiltinNodeDescriptorProvider().getDescriptors().stream()
                .filter(descriptor -> descriptor.type() == FlowNodeType.APPROVAL)
                .findFirst().orElseThrow()
                .configSchema();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String field) {
        return (Map<String, Object>) schema.get(field);
    }
}
