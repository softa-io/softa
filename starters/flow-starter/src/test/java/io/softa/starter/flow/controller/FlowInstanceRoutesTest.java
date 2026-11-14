package io.softa.starter.flow.controller;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the method and path of every instance, approval task and monitor endpoint. The permission manifests of
 * the applications that embed the engine list these pairs, so a change here ships with theirs.
 */
class FlowInstanceRoutesTest {

    @Test
    void instanceEndpoints() {
        assertEquals(new TreeSet<>(Set.of(
                        "POST /flow/instances/start",
                        "GET /flow/instances/{instanceId}",
                        "GET /flow/instances/{instanceId}/overlay",
                        "GET /flow/instances/{instanceId}/trace",
                        "POST /flow/instances/search",
                        "POST /flow/instances/approve",
                        "POST /flow/instances/reject",
                        "POST /flow/instances/batch/approve",
                        "POST /flow/instances/batch/return",
                        "POST /flow/instances/batch/reject",
                        "POST /flow/instances/transfer",
                        "POST /flow/instances/cc",
                        "POST /flow/instances/cc/read",
                        "POST /flow/instances/return",
                        "POST /flow/instances/withdraw",
                        "POST /flow/instances/urge",
                        "GET /flow/instances/{instanceId}/urge",
                        "GET /flow/instances/{instanceId}/withdraw",
                        "POST /flow/instances/comment",
                        "GET /flow/instances/{instanceId}/nodes/{nodeId}/formPermissions",
                        "GET /flow/instances/by-record",
                        "GET /flow/instances/preview",
                        "GET /flow/instances/{instanceId}/record",
                        "GET /flow/instances/by-record/record",
                        "GET /flow/instances/{instanceId}/summary",
                        "GET /flow/instances/{instanceId}/design")),
                routes(FlowInstanceController.class));
    }

    @Test
    void monitorEndpoints() {
        assertEquals(new TreeSet<>(Set.of(
                        "POST /flow/monitor/instances/search",
                        "POST /flow/monitor/instances/urge",
                        "POST /flow/monitor/instances/retry",
                        "POST /flow/monitor/instances/terminate",
                        "POST /flow/monitor/approvers/reassign/preview",
                        "POST /flow/monitor/approvers/reassign",
                        "POST /flow/monitor/delegations",
                        "POST /flow/monitor/delegations/search",
                        "POST /flow/monitor/delegations/{id}/cancel")),
                routes(FlowMonitorController.class));
    }

    @Test
    void approvalTaskEndpoints() {
        // The approval center's lists read through POST /search; the GETs it replaced (/pending, /completed, /cc)
        // are gone.
        assertEquals(new TreeSet<>(Set.of(
                        "GET /flow/approvalTasks/counts",
                        "POST /flow/approvalTasks/search",
                        "GET /flow/approvalTasks/instance/{instanceId}")),
                routes(FlowApprovalTaskController.class));
    }

    private static Set<String> routes(Class<?> controller) {
        String base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class).path()[0];
        Set<String> routes = new TreeSet<>();
        for (Method method : controller.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null) {
                routes.add(mapping.method()[0] + " " + base + mapping.path()[0]);
            }
        }
        return routes;
    }
}
