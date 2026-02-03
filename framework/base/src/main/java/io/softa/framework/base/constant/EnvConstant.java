package io.softa.framework.base.constant;

import java.util.Set;

public interface EnvConstant {
    String USER_ID = "USER_ID";
    String USER_EMP_ID = "USER_EMP_ID";
    String USER_POSITION_ID = "USER_POSITION_ID";
    String USER_DEPT_ID = "USER_DEPT_ID";
    String USER_COMP_ID = "USER_COMP_ID";

    String NOW = "NOW";
    String TODAY = "TODAY";

    Set<String> TIME_PARAMS = Set.of(NOW, TODAY);
    Set<String> EMP_INFO_PARAMS = Set.of(USER_EMP_ID, USER_POSITION_ID, USER_DEPT_ID, USER_COMP_ID);
    Set<String> ENV_PARAMS = Set.of(USER_ID, USER_EMP_ID, USER_POSITION_ID, USER_DEPT_ID, USER_COMP_ID, NOW, TODAY);
}