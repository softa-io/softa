package io.softa.starter.user.service;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.Permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EndpointIndexTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ModelService modelService;
    private NavigationModelResolver navResolver;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        navResolver = mock(NavigationModelResolver.class);
    }

    private static Permission explicit(String id, String... endpoints) {
        Permission p = new Permission();
        p.setId(id);
        ArrayNode arr = JSON.arrayNode();
        for (String e : endpoints) arr.add(e);
        p.setEndpoints(arr);
        return p;
    }

    @SuppressWarnings("unchecked")
    private EndpointIndex build(List<Permission> permissions) {
        when(modelService.searchList(
                eq("Permission"), any(FlexQuery.class), eq(Permission.class)))
                .thenReturn(permissions);
        EndpointIndex idx = new EndpointIndex(modelService, navResolver);
        idx.init();
        return idx;
    }

    // ─── validateExplicitEndpoint invariants (fail-loud at startup) ───

    @Test
    void endpointMissingSlashPrefix_throws() {
        assertThatThrownBy(() -> build(List.of(explicit("bad", "POST Employee/searchList"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must start with '/'");
    }

    @Test
    void endpointWithApiPrefix_throws() {
        assertThatThrownBy(() -> build(List.of(explicit("bad", "POST /api/Employee/searchList"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must NOT include the '/api' prefix");
    }

    @Test
    void endpointWithUnknownVerb_throws() {
        assertThatThrownBy(() -> build(List.of(explicit("bad", "FETCH /Employee/searchList"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown HTTP verb");
    }

    @Test
    void endpointWithMissingSpace_throws() {
        assertThatThrownBy(() -> build(List.of(explicit("bad", "POST/Employee/searchList"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void endpointBlank_throws() {
        assertThatThrownBy(() -> build(List.of(explicit("bad", ""))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }

    // ─── lookup: exact-index hits ───

    @Test
    void lookup_exactMatch_returnsPermissionSet() {
        EndpointIndex idx = build(List.of(explicit("emp.view", "POST /Employee/searchList")));
        assertThat(idx.lookup("/Employee/searchList", "POST"))
                .containsExactly("emp.view");
    }

    @Test
    void lookup_notRegistered_returnsEmpty() {
        EndpointIndex idx = build(List.of(explicit("emp.view", "POST /Employee/searchList")));
        assertThat(idx.lookup("/Employee/somethingElse", "POST")).isEmpty();
    }

    @Test
    void lookup_verbMismatch_returnsEmpty() {
        EndpointIndex idx = build(List.of(explicit("emp.view", "POST /Employee/searchList")));
        assertThat(idx.lookup("/Employee/searchList", "GET")).isEmpty();
    }

    @Test
    void lookup_multiplePermissionsShareEndpoint_bothIntoSet() {
        // Both employee.view and department.view claim /Department/searchList
        // so an Employee-only role still gets the department-tree side panel.
        EndpointIndex idx = build(List.of(
                explicit("emp.view", "POST /Department/searchList"),
                explicit("dept.view", "POST /Department/searchList")));
        Set<String> hit = idx.lookup("/Department/searchList", "POST");
        assertThat(hit).containsExactlyInAnyOrder("emp.view", "dept.view");
    }

    // ─── lookup: pattern index ───

    @Test
    void lookup_patternWithPathParam_matchesConcreteRequest() {
        EndpointIndex idx = build(List.of(
                explicit("emp.update", "POST /Employee/onChange/{fieldName}")));
        assertThat(idx.lookup("/Employee/onChange/firstName", "POST"))
                .containsExactly("emp.update");
    }

    @Test
    void lookup_patternMismatch_returnsEmpty() {
        EndpointIndex idx = build(List.of(
                explicit("emp.update", "POST /Employee/onChange/{fieldName}")));
        // Different model → no match.
        assertThat(idx.lookup("/Department/onChange/anything", "POST")).isEmpty();
    }

    @Test
    void lookup_multiplePatternsMatchSameUri_collectedIntoSet() {
        // Two permissions register the same pattern → both surface for the caller.
        EndpointIndex idx = build(List.of(
                explicit("perm.a", "POST /X/{id}/preview"),
                explicit("perm.b", "POST /X/{id}/preview")));
        Set<String> hit = idx.lookup("/X/123/preview", "POST");
        assertThat(hit).containsExactlyInAnyOrder("perm.a", "perm.b");
    }

    @Test
    void lookup_exactPreferredOverPattern() {
        // Exact match wins — the pattern is checked only after exact miss.
        EndpointIndex idx = build(List.of(
                explicit("perm.exact", "POST /X/list"),
                explicit("perm.pat",   "POST /X/{whatever}")));
        Set<String> hit = idx.lookup("/X/list", "POST");
        // Only the exact match returns; pattern isn't consulted.
        assertThat(hit).containsExactly("perm.exact");
    }
}
