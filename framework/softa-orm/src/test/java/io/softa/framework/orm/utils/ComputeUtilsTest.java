package io.softa.framework.orm.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.orm.enums.FieldType;

@Slf4j
class ComputeUtilsTest {

    /**
     * Security regression: the reflective function-missing handler was removed, so
     * arbitrary instance-method reflection on scope objects must no longer resolve.
     * Legitimate functions are reachable only via importFunctions (namespaced form).
     */
    @Test
    void reflectionVectorsAreBlocked() {
        Map<String, Object> env = new HashMap<>();
        env.put("s", "abc");
        Assertions.assertThrows(RuntimeException.class, () -> ComputeUtils.execute("getClass(s)", env));
        Assertions.assertThrows(RuntimeException.class, () -> ComputeUtils.execute("getBytes(s)", env));
        Assertions.assertThrows(RuntimeException.class,
                () -> ComputeUtils.execute("getClassLoader(getClass(s))", env));
    }

    /**
     * Import LocalDate static methods and instance methods
     */
    @Test
    void getNowDate() {
        String formula = "LocalDate.now()";
        Object result = ComputeUtils.execute(formula);
        Assertions.assertEquals(LocalDate.now(), result);
    }

    /**
     * Import LocalDate static methods and instance methods
     */
    @Test
    void parseStringDate() {
        String formula = "LocalDate.parse('2022-11-11')";
        Object result = ComputeUtils.execute(formula);
        Assertions.assertEquals(LocalDate.parse("2022-11-11"), result);
    }

    /**
     * Import LocalDate, DateTimeFormatter static methods and instance methods.
     * Does not support chaining calls DateTimeFormatter.ofPattern('yyyy-MM').format(date).
     */
    @Test
    void convertDate() {
        // String formula = "a = DateTimeFormatter.ofPattern('yyyy-MM');DateTimeFormatter.format(a, date)";
        String formula = "LocalDate.format(date, DateTimeFormatter.ofPattern('yyyy-MM'))";
        LocalDate date = LocalDate.now();
        Map<String, Object> env = new HashMap<>();
        env.put("date", date);
        Object result = ComputeUtils.execute(formula, env);
        Assertions.assertEquals(DateTimeFormatter.ofPattern("yyyy-MM").format(date), result);
    }

    /**
     * Import LocalDate static methods and instance methods
     */
    @Test
    void computeDateYear() {
        String formula = "LocalDate.plusYears(date, 5)";
        LocalDate date = LocalDate.now();
        Map<String, Object> env = new HashMap<>();
        env.put("date", date);
        Object result = ComputeUtils.execute(formula, env);
        Assertions.assertEquals(date.plusYears(5), result);
    }

    /**
     * Compute the days difference between two LocalDateTime objects:
     *  localDateTime2.toLocalDate().toEpochDay() - localDateTime1.toLocalDate().toEpochDay()
     */
    @Test
    void computeDateDiff() {
        String formula = "LocalDate.toEpochDay(LocalDateTime.toLocalDate(LocalDateTime.now())) "
                + "- LocalDate.toEpochDay(LocalDateTime.toLocalDate(dateTime1))";
        LocalDateTime dateTime1 = LocalDateTime.now().minusDays(2);
        Map<String, Object> env = new HashMap<>();
        env.put("dateTime1", dateTime1);
        Object days = ComputeUtils.execute(formula, env);
        Assertions.assertEquals(2L, days);
    }

    /**
     * Compute the days difference between two LocalDateTime objects:
     *  ChronoUnit.DAYS.between(localDateTime1, localDateTime2)
     */
    @Test
    void computeDateDiffUsingChronUnit() {
        String formula = "ChronoUnit.between(ChronoUnit.DAYS, dateTime1, LocalDateTime.now())";
        LocalDateTime dateTime1 = LocalDateTime.now().minusDays(2);
        Map<String, Object> env = new HashMap<>();
        env.put("dateTime1", dateTime1);
        Object days = ComputeUtils.execute(formula, env);
        Assertions.assertEquals(2L, days);
    }

    @Test
    void getFromList() {
        String formula = "data[0]";
        List<String> data = new ArrayList<>(Arrays.asList("a", "b", "c"));
        Map<String, Object> env = new HashMap<>();
        env.put("data", data);
        Object result = ComputeUtils.execute(formula, env);
        Assertions.assertEquals(data.getFirst(), result);
    }

    @Test
    void getFromMap() {
        String formula = "data.a";
        Map<String, Object> data = Map.of("a", "Tab", "c", 2);
        Map<String, Object> env = new HashMap<>();
        env.put("data", data);
        Object result = ComputeUtils.execute(formula, env);
        Assertions.assertEquals(data.get("a"), result);
    }

    @Test
    void getFromMap2() {
        String formula = "data.a.b";
        String b = "Max";
        Map<String, Object> data = Map.of("a", Map.of("b", b), "c", 2);
        Map<String, Object> env = new HashMap<>();
        env.put("data", data);
        Object result = ComputeUtils.execute(formula, env);
        Assertions.assertEquals(b, result);
    }

    @Test
    void decimal() {
        String formula = " 1 / 3 * 3";
        Object result1 = ComputeUtils.execute(formula);
        BigDecimal result2 = new BigDecimal("1").divide(new BigDecimal("3"), 16, RoundingMode.HALF_EVEN).multiply(new BigDecimal("3"));
        Assertions.assertEquals(result1, result2);
    }

    @Test
    void validateFormula() {
        String formula = " 1 / 3 , 3;";
        Assertions.assertFalse(ComputeUtils.validateExpression(formula));
    }

    /**
     * `ChronoUnit.X` is parsed by Aviator as variable `ChronoUnit` with property `X`.
     * Since the framework injects `ChronoUnit` into env at runtime via ChronoUnitUtils,
     * those names must be excluded from the dependent-field list returned by getVariables —
     * otherwise metadata field-validation will reject expressions like
     * `between(ChronoUnit.YEARS, originalHireDate, LocalDate.now())`.
     */
    @Test
    void getVariablesExcludesChronoUnitNamespace() {
        String formula = "between(ChronoUnit.YEARS, hireDate, LocalDate.now())"
                + " + between(ChronoUnit.MONTHS, hireDate, LocalDate.now())";
        List<String> variables = ComputeUtils.getVariables(formula);
        Assertions.assertFalse(variables.stream().anyMatch(v -> v.startsWith("ChronoUnit")),
                "ChronoUnit.* must not appear in variables: " + variables);
        Assertions.assertTrue(variables.contains("hireDate"));
    }

    @Test
    void stringInterpolation() {
        Map<String, Object> env = new HashMap<>();
        env.put("TriggerParams", Map.of("id", 1001, "status", "PAID"));
        String result = ComputeUtils.stringInterpolation(
                "Order {{ TriggerParams.id }} is {{ TriggerParams.status }}", env);
        Assertions.assertEquals("Order 1001 is PAID", result);
    }

    /**
     * A computed OPTION field: the expression yields the item code. There is no ValueType for OPTION,
     * so this used to throw on every write of a stored computed option field.
     */
    @Test
    void anOptionResultIsTheItemCode() {
        String formula = "if (linkedToId == nil) { return \"Regular\"; } \"Linked\"";
        Map<String, Object> env = new HashMap<>();
        env.put("linkedToId", 1001L);
        Assertions.assertEquals("Linked", ComputeUtils.execute(formula, env, 0, FieldType.OPTION));
        env.put("linkedToId", null);
        Assertions.assertEquals("Regular", ComputeUtils.execute(formula, env, 0, FieldType.OPTION));
    }

    /** No result is no option, not the empty string the STRING type would default to. */
    @Test
    void noOptionResultStaysNull() {
        Assertions.assertNull(ComputeUtils.execute("nil", new HashMap<>(), 0, FieldType.OPTION));
    }
}
