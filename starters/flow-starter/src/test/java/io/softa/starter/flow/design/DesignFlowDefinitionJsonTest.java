package io.softa.starter.flow.design;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

import static org.junit.jupiter.api.Assertions.*;

class DesignFlowDefinitionJsonTest {

    // As the application reads stored designs (JacksonConfig): a key the model no longer has reads as absent.
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    @Test
    void shouldDeserializeUsingNewEnumValues() throws Exception {
        String json = """
                {
                  "name": "Leave Flow",
                  "scenario": "Automation",
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

        assertEquals(FlowScenario.AUTOMATION, definition.getScenario());
        assertInstanceOf(ApiTrigger.class, definition.getTrigger());
        // A design saved with form bindings, which flows no longer have, still reads.
        assertEquals(FlowNodeType.START, definition.getGraph().getNodes().getFirst().getType());
    }

    @Test
    void shouldDeserializeFlowScenarioCaseInsensitively() throws Exception {
        assertEquals(FlowScenario.APPROVAL, objectMapper.readValue("\"Approval\"", FlowScenario.class));
        assertEquals(FlowScenario.AUTOMATION, objectMapper.readValue("\"Automation\"", FlowScenario.class));
        assertEquals(FlowScenario.VALIDATION, objectMapper.readValue("\"Validation\"", FlowScenario.class));
        assertEquals(FlowScenario.COMPUTE, objectMapper.readValue("\"Compute\"", FlowScenario.class));
    }

    @Test
    void shouldDeserializeNodeTypeCaseInsensitively() throws Exception {
        assertEquals(FlowNodeType.START, objectMapper.readValue("\"Start\"", FlowNodeType.class));
        assertEquals(FlowNodeType.APPROVAL, objectMapper.readValue("\"Approval\"", FlowNodeType.class));
        assertEquals(FlowNodeType.CALL_SERVICE, objectMapper.readValue("\"CallService\"", FlowNodeType.class));
    }

    @Test
    void shouldSerializeUsingNewEnumValues() throws Exception {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Leave Flow")
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest"))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(FlowGraphNode.builder()
                                .id("approval")
                                .type(FlowNodeType.APPROVAL)
                                .label("Approval")
                                .build()))
                        .edges(List.of())
                        .build())
                .build();

        String json = objectMapper.writeValueAsString(definition);

        assertTrue(json.contains("\"scenario\":\"Approval\""));
        assertTrue(json.contains("\"type\":\"Submit\""));
        assertTrue(json.contains("\"type\":\"Approval\""));
    }

    @Test
    void shouldRoundTripTriggerSourcePolymorphically() throws Exception {
        DesignFlowDefinition definition = DesignFlowDefinition.builder()
                .name("Test Flow")
                .scenario(FlowScenario.AUTOMATION)
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
