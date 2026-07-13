package io.softa.starter.flow.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.enums.FlowFormUsage;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

import static org.junit.jupiter.api.Assertions.*;

class DesignFlowDefinitionJsonTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @Test
    void shouldDeserializeUsingNewEnumValues() throws Exception {
        String json = """
                {
                  "code": "leave-flow",
                  "name": "Leave Flow",
                  "scenario": "Process",
                  "trigger": {
                    "type": "Api",
                    "description": null
                  },
                  "graph": {
                    "nodes": [
                      {
                        "id": "start",
                        "type": "Start",
                        "label": "Start"
                      }
                    ],
                    "edges": []
                  },
                  "forms": [
                    {
                      "formCode": "leave-request",
                      "usage": "StartForm"
                    }
                  ]
                }
                """;

        DesignFlowDefinition definition = objectMapper.readValue(json, DesignFlowDefinition.class);

        assertEquals(FlowScenario.PROCESS, definition.getScenario());
        assertInstanceOf(ApiTrigger.class, definition.getTrigger());
        assertEquals(FlowNodeType.START, definition.getGraph().getNodes().getFirst().getType());
        assertEquals(FlowFormUsage.START_FORM, definition.getForms().getFirst().getUsage());
    }

    @Test
    void shouldDeserializeFlowScenarioCaseInsensitively() throws Exception {
        assertEquals(FlowScenario.PROCESS, objectMapper.readValue("\"Process\"", FlowScenario.class));
        assertEquals(FlowScenario.VALIDATION, objectMapper.readValue("\"Validation\"", FlowScenario.class));
        assertEquals(FlowScenario.COMPUTE, objectMapper.readValue("\"Compute\"", FlowScenario.class));
    }

    @Test
    void shouldDeserializeNodeTypeCaseInsensitively() throws Exception {
        assertEquals(FlowNodeType.START, objectMapper.readValue("\"Start\"", FlowNodeType.class));
        assertEquals(FlowNodeType.APPROVAL, objectMapper.readValue("\"Approval\"", FlowNodeType.class));
        assertEquals(FlowNodeType.CALL_SERVICE, objectMapper.readValue("\"CallService\"", FlowNodeType.class));
        assertEquals(FlowFormUsage.READONLY_VIEW, objectMapper.readValue("\"ReadonlyView\"", FlowFormUsage.class));
    }

    @Test
    void shouldSerializeUsingNewEnumValues() throws Exception {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("leave-flow")
                .name("Leave Flow")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(FlowGraphNode.builder()
                                .id("approval")
                                .type(FlowNodeType.APPROVAL)
                                .label("Approval")
                                .build()))
                        .edges(List.of())
                        .build())
                .forms(List.of(FlowFormBinding.builder()
                        .formCode("leave-request")
                        .usage(FlowFormUsage.START_FORM)
                        .build()))
                .build();

        String json = objectMapper.writeValueAsString(definition);

        assertTrue(json.contains("\"scenario\":\"Process\""));
        assertTrue(json.contains("\"type\":\"Api\""));
        assertTrue(json.contains("\"type\":\"Approval\""));
        assertTrue(json.contains("\"usage\":\"StartForm\""));
    }

    @Test
    void shouldRoundTripTriggerSourcePolymorphically() throws Exception {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .code("test-flow")
                .name("Test Flow")
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger("leave-form"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of())
                        .edges(List.of())
                        .build())
                .build();

        String json = objectMapper.writeValueAsString(definition);
        DesignFlowDefinition restored = objectMapper.readValue(json, DesignFlowDefinition.class);

        assertInstanceOf(ApiTrigger.class, restored.getTrigger());
        assertEquals("leave-form", ((ApiTrigger) restored.getTrigger()).description());
    }
}
