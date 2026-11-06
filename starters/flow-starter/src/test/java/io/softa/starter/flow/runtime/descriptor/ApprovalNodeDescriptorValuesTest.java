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

    private static final List<String> ENUM_FIELDS = List.of("emptyApproverStrategy", "approvalMode");

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
        for (String field : List.of("minCount", "percentage")) {
            Map<?, ?> depends = (Map<?, ?>) property(schema, field).get("depends");
            depends.forEach((on, value) -> assertTrue(
                    ((List<?>) property(schema, (String) on).get("options")).contains(value),
                    field + " depends on " + on + " = " + value + ", which " + on + " does not offer"));
        }
    }

    /**
     * Who approves is one or more groups, each one choice — specific people first, then the sources that find them —
     * and a new step starts with one group on specific people; there is no separate list of named approvers, nor a
     * single source.
     */
    @Test
    void approversAreGroupsStartingOnSpecificPeople() {
        Map<String, Object> schema = approvalSchema();
        assertFalse(schema.containsKey("approvers"));
        assertFalse(schema.containsKey("approverSource"));
        assertEquals("approverSources", property(schema, "approverSources").get("type"));
        assertEquals(true, property(schema, "approverSources").get("required"));
        Map<?, ?> first = (Map<?, ?>) ((List<?>) property(schema, "approverSources").get("options")).getFirst();
        assertEquals("Users", first.get("type"));
        assertEquals("Specific people", first.get("label"));
        assertEquals(Map.of("type", "userPicker", "label", "People", "multiple", true, "required", true),
                ((Map<?, ?>) first.get("fields")).get("userIds"));

        Map<String, Object> defaults = new BuiltinNodeDescriptorProvider().getDescriptors().stream()
                .filter(descriptor -> descriptor.type() == FlowNodeType.APPROVAL)
                .findFirst().orElseThrow()
                .defaultConfig();
        assertEquals(Map.of("approverSources", List.of(Map.of("type", "Users"))), defaults);
        assertReadable("approverSources", defaults.get("approverSources"));
    }

    /**
     * The sources come in the order an HR administrator reaches for them — specific people, the organization's, the
     * groups', then the developers' — and each says what the designer needs to tell which settings apply: where specific
     * people are named, and that a source finds one person at most.
     */
    @Test
    void sourcesComeInTheOrderTheyAreReachedForAndSayWhatTheyFind() {
        List<?> options = (List<?>) property(approvalSchema(), "approverSources").get("options");
        List<?> offered = options.stream().map(option -> (Map<?, ?>) option)
                .filter(option -> !Boolean.TRUE.equals(option.get("deprecated"))).map(option -> option.get("label")).toList();
        assertEquals(List.of("Specific people", "Supervisor", "Department leader", "Department HRBP", "Role", "Position",
                "Department members", "Variable list (advanced)", "Expression (advanced)"), offered);

        Map<Object, Map<?, ?>> byType = new java.util.LinkedHashMap<>();
        options.forEach(option -> byType.put(((Map<?, ?>) option).get("type"), (Map<?, ?>) option));
        assertEquals("userIds", byType.get("Users").get("names"));
        assertEquals(List.of("InitiatorManager", "Supervisor", "DeptLeader", "DeptHrbp"), byType.values().stream()
                .filter(option -> Boolean.TRUE.equals(option.get("findsOne"))).map(option -> option.get("type"))
                .sorted(java.util.Comparator.comparing(type -> List.of("InitiatorManager", "Supervisor", "DeptLeader",
                        "DeptHrbp").indexOf(type))).toList());
    }

    /**
     * Who approves, then how many of them must, then what a step that finds nobody does — each setting saying when it
     * applies: the mode where the step may have several approvers, the empty-approver settings where a group finds its
     * people by rule.
     */
    @Test
    void settingsFollowTheApproversAndSayWhenTheyApply() {
        Map<String, Object> schema = approvalSchema();
        assertEquals(List.of("approverSources", "approvalMode", "minCount", "percentage", "emptyApproverStrategy",
                "emptyApproverRoleIds", "emptyApproverRoleCodes", "formPermissions"), List.copyOf(schema.keySet()));
        for (String field : List.of("approvalMode", "minCount", "percentage")) {
            assertEquals("severalApprovers", property(schema, field).get("appliesWhen"), field);
        }
        for (String field : List.of("emptyApproverStrategy", "emptyApproverRoleIds", "emptyApproverRoleCodes")) {
            assertEquals("approversFoundByRule", property(schema, field).get("appliesWhen"), field);
        }
        assertEquals(Map.of("AnyOne", "Any one", "Unanimous", "Everyone", "MinCount", "Minimum number",
                "Percentage", "Percentage"), property(schema, "approvalMode").get("optionLabels"));
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
