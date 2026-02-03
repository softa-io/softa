package io.softa.framework.orm.domain;

import tools.jackson.core.type.TypeReference;
import io.softa.framework.base.utils.JsonUtils;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
public class FiltersParserBenchmarkTest {

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Measurement(iterations = 10000, time = 1, timeUnit = TimeUnit.MICROSECONDS)
    public void parseFiltersByANTLR() {
        String semanticString = "((name = \"Te st\" AND code IN [\"A01\"]) OR version NOT IN [1]) AND version2 != 21";
        Filters filters = Filters.ofSemantic(semanticString);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Measurement(iterations = 10000, time = 1, timeUnit = TimeUnit.MICROSECONDS)
    public void parseFiltersByJSON() {
        String structuredString = "[[[[\"name\",\"=\",\"Te st\"],\"AND\",[\"code\",\"IN\",[\"A01\"]]],\"OR\",[\"version\",\"NOT IN\",[1]]],\"AND\",[\"version2\",\"!=\",21]]";
        Filters filters = Filters.of(structuredString);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Measurement(iterations = 10000, time = 1, timeUnit = TimeUnit.MICROSECONDS)
    public void parseFiltersByInternal() {
        String structuredString = "[[[\"name\",\"=\",\"Te st\"],\"AND\",[\"code\",\"IN\",[\"A01\"]]],\"OR\",[\"version\",\"NOT IN\",[1]]],\"AND\",[\"version2\",\"!=\",21]]";
        List<Object> filterList = JsonUtils.stringToObject(structuredString, new TypeReference<>() {});
        Filters filters = Filters.of(filterList);
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(FiltersParserBenchmarkTest.class.getSimpleName())
                .forks(1)
                .build();

        new Runner(opt).run();
    }
}
