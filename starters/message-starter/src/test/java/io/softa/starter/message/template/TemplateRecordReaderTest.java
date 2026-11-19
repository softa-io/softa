package io.softa.starter.message.template;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.placeholder.TemplateBlocks;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.SubQuery;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.vo.ModelReference;
import io.softa.framework.orm.vo.OptionReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateRecordReaderTest {

    private ModelService<Serializable> modelService;
    private TemplateRecordReader reader;
    /** Every query the reader ran, with the context it ran in. */
    private final List<FlexQuery> queries = new ArrayList<>();
    private final List<Boolean> skippedPermissions = new ArrayList<>();
    private final List<Boolean> masked = new ArrayList<>();
    private final Map<ConvertType, Map<String, Object>> rows = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelService = mock(ModelService.class);
        reader = new TemplateRecordReader(modelService, new TemplateTestModels());
        when(modelService.searchList(eq("Order"), any(FlexQuery.class))).thenAnswer(invocation -> {
            FlexQuery query = invocation.getArgument(1);
            queries.add(query);
            skippedPermissions.add(ContextHolder.getContext().isSkipPermissionCheck());
            masked.add(ContextHolder.getContext().isDataMask());
            Map<String, Object> row = rows.get(query.getConvertType());
            return row == null ? List.of() : List.of(row);
        });
    }

    private Map<String, Object> read(String subject, String html) {
        Context caller = new Context("trace-1");
        caller.setDataMask(true);
        try (MockedStatic<IdUtils> ids = Mockito.mockStatic(IdUtils.class)) {
            ids.when(() -> IdUtils.formatId(eq("Order"), any(Serializable.class))).thenAnswer(i -> i.getArgument(1));
            return ContextHolder.callWith(caller, () -> reader.read("Order", 7L, TemplateUsage.ofMail(subject, html, null)));
        }
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void readsExactlyTheFieldsTheTemplatePrints_asDisplayValuesShapedLikeItsNames() {
        rows.put(ConvertType.DISPLAY, map("id", 7L, "code", "SO-7", "status", "Approved",
                "dueDate", LocalDate.of(2026, 11, 20),
                "customerId", map("id", 3L, "name", "Acme", "managerId", map("id", 9L, "name", "Alex"))));

        Map<String, Object> variables = read("{{ code }}",
                "{{ status }} {{ dueDate }} {{ customerId.name }} {{ customerId.managerId.name }}");

        assertThat(variables).containsEntry("code", "SO-7").containsEntry("status", "Approved")
                .containsEntry("dueDate", LocalDate.of(2026, 11, 20));
        assertThat(variables.get("customerId")).isEqualTo(Map.of("name", "Acme", "managerId", Map.of("name", "Alex")));
        assertThat(variables).doesNotContainKey(TemplateBlocks.RAW_ROOT);

        assertThat(queries).hasSize(1);
        FlexQuery query = queries.getFirst();
        assertThat(query.getConvertType()).isEqualTo(ConvertType.DISPLAY);
        assertThat(query.getFields()).containsExactlyInAnyOrder("id", "code", "status", "dueDate", "customerId");
        SubQuery customer = query.getSubQueries().getQueryMap().get("customerId");
        assertThat(customer.getFields()).containsExactlyInAnyOrder("id", "name", "managerId");
        assertThat(customer.getSubQueries().get("managerId").getFields()).containsExactlyInAnyOrder("id", "name");
    }

    @Test
    void readsAsTheSystemAndUnmasked_whoeverTriggersTheSend() {
        rows.put(ConvertType.DISPLAY, map("id", 7L, "code", "SO-7"));

        read("{{ code }}", null);

        assertThat(skippedPermissions).containsExactly(true);
        assertThat(masked).containsExactly(false);
    }

    @Test
    void aRelationPrintedOnItsOwnShowsItsDisplayName_andAMissingValuePrintsNothing() {
        rows.put(ConvertType.DISPLAY, map("id", 7L, "customerId", "Acme", "code", null));

        Map<String, Object> variables = read(null, "{{ customerId }} {{ code }}");

        assertThat(variables).containsEntry("customerId", "Acme").containsEntry("code", "");
    }

    @Test
    void aCopiedFieldIsReadAtItsOrigin_andLandsUnderTheNameTheTemplateUses() {
        rows.put(ConvertType.DISPLAY, map("id", 7L,
                "customerId", map("id", 3L, "managerId", map("id", 9L, "name", "Alex"))));

        Map<String, Object> variables = read(null, "{{ managerId.name }}");

        assertThat(variables.get("managerId")).isEqualTo(Map.of("name", "Alex"));
        assertThat(queries.getFirst().getFields()).containsExactlyInAnyOrder("id", "customerId");
    }

    @Test
    void aFieldTheModelNoLongerHasIsLeftOutAndPrintsBlank() {
        rows.put(ConvertType.DISPLAY, map("id", 7L, "code", "SO-7"));

        Map<String, Object> variables = read(null, "{{ code }} {{ removedField }} {{ customerId.gone }}");

        assertThat(variables).containsOnlyKeys("code");
        assertThat(queries.getFirst().getFields()).containsExactlyInAnyOrder("id", "code");
    }

    @Test
    void aLoopTableGetsItsRowsWithTheColumnsItShows() {
        rows.put(ConvertType.DISPLAY, map("id", 7L, "lines", List.of(
                map("id", 1L, "name", "Pen", "qty", 2, "productId", map("id", 5L, "code", "P-1")),
                map("id", 2L, "name", "Ink", "qty", 1, "productId", null))));
        String table = "<table data-tpl-loop=\"lines\"><thead><tr><th data-tpl-field=\"name\">N</th>"
                + "<th data-tpl-field=\"qty\">Q</th><th data-tpl-field=\"productId.code\">C</th></tr></thead>"
                + "<tbody><tr><td>{{name}}</td><td>{{qty}}</td><td>{{productId.code}}</td></tr></tbody></table>";

        Map<String, Object> variables = read(null, table);

        assertThat(variables.get("lines")).isEqualTo(List.of(
                Map.of("name", "Pen", "qty", 2, "productId", Map.of("code", "P-1")),
                Map.of("name", "Ink", "qty", 1, "productId", Map.of("code", ""))));
        SubQuery lines = queries.getFirst().getSubQueries().getQueryMap().get("lines");
        assertThat(lines.getFields()).containsExactlyInAnyOrder("id", "name", "qty", "productId");
        assertThat(lines.getSubQueries().get("productId").getFields()).containsExactlyInAnyOrder("id", "code");
    }

    @Test
    void conditionsGetTheValuesAsStored_underTheirOwnRoot() {
        rows.put(ConvertType.DISPLAY, map("id", 7L, "status", "Approved by manager"));
        rows.put(ConvertType.REFERENCE, map("id", 7L, "status", OptionReference.of("APPROVED", "Approved by manager"),
                "urgent", true, "amount", 1200, "customerId", ModelReference.of(3L, "Acme"),
                "lines", List.of(map("id", 1L))));
        String html = "<p>{{ status }}</p>"
                + "<div data-tpl-if=\"status\" data-tpl-op=\"eq\" data-tpl-value=\"APPROVED\"><p>a</p></div>"
                + "<div data-tpl-if=\"urgent\" data-tpl-op=\"isTrue\"><p>b</p></div>"
                + "<div data-tpl-if=\"amount\" data-tpl-op=\"gt\" data-tpl-value=\"1000\" data-tpl-value-type=\"number\"><p>c</p></div>"
                + "<div data-tpl-if=\"customerId\" data-tpl-op=\"isNotEmpty\"><p>d</p></div>"
                + "<div data-tpl-if=\"lines\" data-tpl-op=\"isNotEmpty\"><p>e</p></div>";

        Map<String, Object> variables = read(null, html);

        assertThat(variables).containsEntry("status", "Approved by manager");
        assertThat(variables.get(TemplateBlocks.RAW_ROOT)).isEqualTo(Map.of(
                "status", "APPROVED", "urgent", true, "amount", 1200, "customerId", 3L, "lines", List.of(1L)));
        assertThat(queries).extracting(FlexQuery::getConvertType)
                .containsExactly(ConvertType.DISPLAY, ConvertType.REFERENCE);
        FlexQuery raw = queries.get(1);
        assertThat(raw.getFields()).containsExactlyInAnyOrder("id", "status", "urgent", "amount", "customerId", "lines");
        // A list is asked only whether it has rows: their ids answer that.
        assertThat(raw.getSubQueries().getQueryMap().get("lines").getFields()).containsExactly("id");
        assertThat(skippedPermissions).containsExactly(true, true);
    }

    @Test
    void theVariablesRenderTheTemplateTheyWereReadFor() {
        rows.put(ConvertType.DISPLAY, map("id", 7L, "code", "SO-7",
                "lines", List.of(map("id", 1L, "name", "Pen", "qty", 2))));
        rows.put(ConvertType.REFERENCE, map("id", 7L, "urgent", true));
        String html = "<p>{{ code }}</p><div data-tpl-if=\"urgent\" data-tpl-op=\"isTrue\"><p>URGENT</p></div>"
                + "<table data-tpl-loop=\"lines\"><thead><tr><th data-tpl-field=\"name\">N</th></tr></thead>"
                + "<tbody><tr><td>{{name}} x {{qty}}</td></tr></tbody></table>";

        Map<String, Object> variables = read(null, html);
        String out = io.softa.framework.base.placeholder.TemplateEngine.renderHtml(TemplateBlocks.toPebble(html), variables);

        assertThat(out).contains("<p>SO-7</p>").contains("URGENT").contains("<td>Pen x 2</td>");
    }

    @Test
    void aRecordThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> read("{{ code }}", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Order record 7 does not exist");
    }
}
