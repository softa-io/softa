package io.softa.framework.base.enums;


import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * UTC-12:00, UTC-12:00 (Baker Island)
 * UTC-11:00, UTC-11:00 (Samoa)
 */
@Getter
@AllArgsConstructor
public enum Timezone {
    UTC_M_12_00("UTC-12:00", "UTC-12:00 (Baker Island)"),
    UTC_M_11_00("UTC-11:00", "UTC-11:00 (Samoa)"),
    UTC_M_10_00("UTC-10:00", "UTC-10:00 (Hawaii)"),
    UTC_M_09_00("UTC-09:00", "UTC-09:00 (Alaska)"),
    UTC_M_08_00("UTC-08:00", "UTC-08:00 (Pacific Time)"),
    UTC_M_07_00("UTC-07:00", "UTC-07:00 (Mountain Time)"),
    UTC_M_06_00("UTC-06:00", "UTC-06:00 (Central Time)"),
    UTC_M_05_00("UTC-05:00", "UTC-05:00 (Eastern Time)"),
    UTC_M_04_00("UTC-04:00", "UTC-04:00 (Atlantic Time)"),
    UTC_M_03_30("UTC-03:30", "UTC-03:30 (Newfoundland)"),
    UTC_M_03_00("UTC-03:00", "UTC-03:00 (Brasilia)"),
    UTC_M_02_00("UTC-02:00", "UTC-02:00 (Mid-Atlantic)"),
    UTC_M_01_00("UTC-01:00", "UTC-01:00 (Azores)"),
    UTC_P_00_00("UTC+00:00", "UTC+00:00 (Greenwich Mean Time)"),
    UTC_P_01_00("UTC+01:00", "UTC+01:00 (Central European Time)"),
    UTC_P_02_00("UTC+02:00", "UTC+02:00 (Eastern European Time)"),
    UTC_P_03_00("UTC+03:00", "UTC+03:00 (Moscow Time)"),
    UTC_P_03_30("UTC+03:30", "UTC+03:30 (Tehran)"),
    UTC_P_04_00("UTC+04:00", "UTC+04:00 (Abu Dhabi)"),
    UTC_P_04_30("UTC+04:30", "UTC+04:30 (Kabul)"),
    UTC_P_05_00("UTC+05:00", "UTC+05:00 (Islamabad)"),
    UTC_P_05_30("UTC+05:30", "UTC+05:30 (Pakistan Standard Time)"),
    UTC_P_05_45("UTC+05:45", "UTC+05:45 (Nepal Time)"),
    UTC_P_06_00("UTC+06:00", "UTC+06:00 (Bangladesh Standard Time)"),
    UTC_P_06_30("UTC+06:30", "UTC+06:30 (Nepal Time)"),
    UTC_P_07_00("UTC+07:00", "UTC+07:00 (Jakarta)"),
    UTC_P_08_00("UTC+08:00", "UTC+08:00 (Hong Kong)"),
    UTC_P_08_30("UTC+08:30", "UTC+08:30 (Singapore)"),
    UTC_P_09_00("UTC+09:00", "UTC+09:00 (Tokyo)"),
    UTC_P_09_30("UTC+09:30", "UTC+09:30 (Australia Central)"),
    UTC_P_10_00("UTC+10:00", "UTC+10:00 (Australian Eastern)"),
    UTC_P_10_30("UTC+10:30", "UTC+10:30 (Lord Howe Island)"),
    UTC_P_11_00("UTC+11:00", "UTC+11:00 (Solomon Islands)"),
    UTC_P_11_30("UTC+11:30", "UTC+11:30 (Midway Island)"),
    UTC_P_12_00("UTC+12:00", "UTC+12:00 (New Zealand)"),
    UTC_P_12_30("UTC+12:30", "UTC+12:30 (Chatham Island)"),
    UTC_P_13_00("UTC+13:00", "UTC+13:00 (Tonga)"),
    UTC_P_14_00("UTC+14:00", "UTC+14:00 (Line Islands)"),
    ;

    @JsonValue
    private final String timezone;

    private final String description;

    private static final Map<String, Timezone> TIMEZONE_MAP = Stream.of(values())
        .collect(Collectors.toMap(Timezone::getTimezone, Function.identity()));

    public static Timezone of(String timezone) {
        return TIMEZONE_MAP.get(timezone);
    }
}
