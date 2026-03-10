package io.softa.framework.base.constant;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import io.softa.framework.base.utils.MapUtils;

public interface EnvConstant {
    String USER_ID = "USER_ID";
    String USER_EMP_ID = "USER_EMP_ID";
    String USER_POSITION_ID = "USER_POSITION_ID";
    String USER_DEPT_ID = "USER_DEPT_ID";
    String USER_COMP_ID = "USER_COMP_ID";

    String NOW = "NOW";
    String TODAY = "TODAY";
    String YESTERDAY = "YESTERDAY";

    Set<String> TIME_PARAMS = Set.of(NOW, TODAY, YESTERDAY);
    Set<String> EMP_INFO_PARAMS = Set.of(USER_EMP_ID, USER_POSITION_ID, USER_DEPT_ID, USER_COMP_ID);
    Set<String> ENV_PARAMS = Set.of(USER_ID, USER_EMP_ID, USER_POSITION_ID, USER_DEPT_ID, USER_COMP_ID, NOW, TODAY, YESTERDAY);

    static Map<String, Object> getEnv() {
        return MapUtils.strObj()
                .put(NOW, LocalDateTime.now())
                .put(TODAY, LocalDate.now())
                .put(YESTERDAY, LocalDate.now().minusDays(1))
                .build();
    }

    static LocalDateTime getNow() {
        return LocalDateTime.now();
    }

    static LocalDate getToday() {
        return LocalDate.now();
    }

    static LocalDate getYesterday() {
        return LocalDate.now().minusDays(1);
    }

}
