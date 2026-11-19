package io.softa.framework.orm.meta;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import fixtures.tplvars.duplicate.First;
import fixtures.tplvars.duplicate.Second;
import fixtures.tplvars.invalid.Invalid;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import io.softa.framework.base.annotation.TemplateVariables;
import io.softa.framework.orm.enums.FieldType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateVariablesRegistryTest {

    public enum Tone { CALM, URGENT }

    public record Line(@Schema(title = "Item") String name, int qty, BigDecimal price) {
    }

    public record Sender(String fullName, @Schema(title = "Work email") String workEmail) {
    }

    @TemplateVariables(name = "test.order-notice", label = "Order notice")
    public record OrderNotice(
            @Schema(title = "Order code", description = "The code the customer quotes", example = "SO-0012")
            String code,
            Long total,
            boolean paid,
            LocalDate dueDate,
            LocalDateTime sentAt,
            Tone tone,
            Sender sender,
            List<Line> lines) {
    }

    @TemplateVariables(name = "test.plain-code")
    public record PlainCode(String code) {
    }

    @AfterEach
    void clear() {
        TemplateVariablesRegistry.register(Set.of());
    }

    @Test
    void describesEachComponentTheWayAModelFieldIsDescribed() {
        TemplateVariablesRegistry.register(Set.of(OrderNotice.class));
        TemplateVariableSet set = TemplateVariablesRegistry.find("test.order-notice").orElseThrow();
        assertThat(set.label()).isEqualTo("Order notice");
        assertThat(set.fields()).extracting(TemplateVariableField::fieldName)
                .containsExactly("code", "total", "paid", "dueDate", "sentAt", "tone", "sender", "lines");
        assertThat(set.fields()).extracting(TemplateVariableField::fieldType).containsExactly(
                FieldType.STRING, FieldType.LONG, FieldType.BOOLEAN, FieldType.DATE, FieldType.DATE_TIME,
                FieldType.OPTION, FieldType.DTO, FieldType.ONE_TO_MANY);
        TemplateVariableField code = set.field("code").orElseThrow();
        assertThat(code.label()).isEqualTo("Order code");
        assertThat(code.description()).isEqualTo("The code the customer quotes");
        assertThat(code.example()).isEqualTo("SO-0012");
        // No @Schema: the label is the humanized name.
        assertThat(set.field("dueDate").orElseThrow().label()).isEqualTo("Due Date");
    }

    @Test
    void aLabelLeftOutIsTheHumanizedLastSegmentOfTheName() {
        TemplateVariablesRegistry.register(Set.of(PlainCode.class));
        assertThat(TemplateVariablesRegistry.find("test.plain-code").orElseThrow().label()).isEqualTo("Plain Code");
    }

    @Test
    void pathsFollowNestedSetsButDoNotEnterLists() {
        TemplateVariablesRegistry.register(Set.of(OrderNotice.class));
        TemplateVariableSet set = TemplateVariablesRegistry.find("test.order-notice").orElseThrow();
        assertThat(set.field("sender.workEmail").orElseThrow().label()).isEqualTo("Work email");
        assertThat(set.field("sender.missing")).isEmpty();
        assertThat(set.field("code.length")).isEmpty();
        assertThat(set.field("lines")).get().matches(TemplateVariableField::isList);
        assertThat(set.field("lines.name")).isEmpty();
        assertThat(set.itemField("lines", "name").orElseThrow().label()).isEqualTo("Item");
        assertThat(set.itemField("lines", "missing")).isEmpty();
        assertThat(set.itemField("sender", "fullName")).isEmpty();
        assertThat(set.field("")).isEmpty();
    }

    @Test
    void findIsEmptyForAnUnknownOrBlankName() {
        TemplateVariablesRegistry.register(Set.of(OrderNotice.class));
        assertThat(TemplateVariablesRegistry.find("test.other")).isEmpty();
        assertThat(TemplateVariablesRegistry.find(" ")).isEmpty();
        assertThat(TemplateVariablesRegistry.find(null)).isEmpty();
        assertThat(TemplateVariablesRegistry.names()).containsExactly("test.order-notice");
    }

    @Test
    void twoClassesDeclaringOneNameAreRefusedNamingBoth() {
        assertThatThrownBy(() -> TemplateVariablesRegistry.register(Set.of(First.class, Second.class)))
                .hasMessageContaining("'test.declared-twice'")
                .hasMessageContaining(First.class.getName())
                .hasMessageContaining(Second.class.getName());
    }

    @Test
    void whatCannotBeDescribedIsRefusedNamingTheClass() {
        assertThatThrownBy(() -> TemplateVariablesRegistry.register(Set.of(Invalid.NotARecord.class)))
                .hasMessageContaining(Invalid.NotARecord.class.getName()).hasMessageContaining("record");
        assertThatThrownBy(() -> TemplateVariablesRegistry.register(Set.of(Invalid.BadType.class)))
                .hasMessageContaining(Invalid.BadType.class.getName()).hasMessageContaining("anything");
        assertThatThrownBy(() -> TemplateVariablesRegistry.register(Set.of(Invalid.BadList.class)))
                .hasMessageContaining(Invalid.BadList.class.getName()).hasMessageContaining("names");
        assertThatThrownBy(() -> TemplateVariablesRegistry.register(Set.of(Invalid.Cycle.class)))
                .hasMessageContaining(Invalid.Cycle.class.getName()).hasMessageContaining("contains itself");
    }

    @Test
    void aFailedRegistrationLeavesTheRegistryAsItWas() {
        TemplateVariablesRegistry.register(Set.of(OrderNotice.class));
        assertThatThrownBy(() -> TemplateVariablesRegistry.register(Set.of(First.class, Second.class)))
                .isInstanceOf(RuntimeException.class);
        assertThat(TemplateVariablesRegistry.names()).containsExactly("test.order-notice");
    }

    @Test
    void anObjectBecomesTheMapATemplateIsRenderedFrom() {
        OrderNotice notice = new OrderNotice("SO-1", 30L, true, LocalDate.of(2026, 11, 20), null, Tone.URGENT,
                new Sender("Alex Chen", null),
                List.of(new Line("Pen", 2, new BigDecimal("1.50")), new Line("Ink", 1, null)));
        Map<String, Object> values = TemplateVariablesRegistry.toMap(notice);
        assertThat(values).containsEntry("code", "SO-1").containsEntry("total", 30L).containsEntry("paid", true)
                .containsEntry("dueDate", LocalDate.of(2026, 11, 20)).containsEntry("tone", Tone.URGENT)
                // A missing value is the empty string, never null.
                .containsEntry("sentAt", "");
        assertThat(values.get("sender")).isEqualTo(Map.of("fullName", "Alex Chen", "workEmail", ""));
        assertThat(values.get("lines")).isEqualTo(List.of(
                Map.of("name", "Pen", "qty", 2, "price", new BigDecimal("1.50")),
                Map.of("name", "Ink", "qty", 1, "price", "")));
        assertThat(TemplateVariablesRegistry.nameOf(notice)).isEqualTo("test.order-notice");
    }

    @Test
    void forAProviderFedTheVariablesAMissingTopLevelValueIsLeftOut() {
        OrderNotice notice = new OrderNotice("SO-1", null, false, null, null, null, new Sender("Alex", null), null);
        Map<String, Object> values = TemplateVariablesRegistry.toMapOmittingMissing(notice);
        assertThat(values).containsOnlyKeys("code", "paid", "sender");
        // Inside a nested record a missing value is still empty: only the top level is a parameter list.
        assertThat(values.get("sender")).isEqualTo(Map.of("fullName", "Alex", "workEmail", ""));
    }

    @Test
    void onlyADeclaredRecordIsTurnedIntoAMap() {
        assertThat(TemplateVariablesRegistry.nameOf(new Sender("a", "b"))).isNull();
        assertThat(TemplateVariablesRegistry.nameOf(null)).isNull();
        assertThatThrownBy(() -> TemplateVariablesRegistry.toMap(new Sender("a", "b")))
                .hasMessageContaining("not a @TemplateVariables record");
        assertThatThrownBy(() -> TemplateVariablesRegistry.toMap(Map.of("a", 1)))
                .hasMessageContaining("not a @TemplateVariables record");
    }

    @Test
    void atStartupTheScannerRegistersWhatTheFrameworkNamespaceDeclares() {
        new TemplateVariablesScanner(new DefaultListableBeanFactory(), new MockEnvironment()).afterPropertiesSet();
        assertThat(TemplateVariablesRegistry.names()).contains("test.order-notice", "test.plain-code")
                .doesNotContain("test.declared-twice");
    }

    @Test
    void atStartupTheScannerAlsoLooksUnderTheApplicationsDiscoveryRoots() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("system.metadata.scan-base-packages[0]", "io.softa")
                .withProperty("system.metadata.scan-base-packages[1]", "fixtures.tplvars.duplicate");
        TemplateVariablesScanner scanner = new TemplateVariablesScanner(new DefaultListableBeanFactory(), environment);
        // Found there, and — being a name declared twice — refused: the application does not start.
        assertThatThrownBy(scanner::afterPropertiesSet).hasMessageContaining("'test.declared-twice'");
    }

    @Test
    void theScannerFindsDeclaredRecordsNestedOnesIncluded() {
        Set<Class<?>> found = TemplateVariablesScanner.scan(Set.of("io.softa.framework.orm.meta"));
        assertThat(found).contains(OrderNotice.class, PlainCode.class);
        assertThat(TemplateVariablesScanner.scan(Set.of("fixtures.tplvars.duplicate")))
                .containsExactlyInAnyOrder(First.class, Second.class);
    }
}
