package io.softa.framework.base.constant;

import io.softa.framework.base.enums.Language;

/**
 * Global base constant
 */
public interface BaseConstant {

    Language DEFAULT_LANGUAGE = Language.EN_US;

    /** Debug parameter in request parameter */
    String DEBUG = "debug";
    /** The default top n value */
    Integer DEFAULT_TOP_N = 1;
    Integer DEFAULT_PAGE_NUMBER = 1;
    Integer DEFAULT_PAGE_SIZE = 50;
    Integer DEFAULT_BATCH_SIZE = 1000;
    Integer MAX_BATCH_SIZE = 10000;
    Integer MAX_EXPORT_SIZE = 100000;
    Integer DEFAULT_NAME_LIST_SIZE = 10;

    /** The default file size limit: 20MB */
    Integer DEFAULT_FILE_SIZE_LIMIT = 20 * 1024 * 1024;

    /** Cascading level restriction for cascade fields, for performance consideration, that is f0.f1.f2.f3.f4 */
    Integer CASCADE_LEVEL = 4;

    Integer DEFAULT_SCALE = 2;

    /** The optionSet code of Boolean field */
    String BOOLEAN_OPTION_SET_CODE = "BooleanValue";

    /** The directory of predefined data, located in src/resources/data-system/ */
    String PREDEFINED_DATA_SYSTEM_DIR = "data-system/";
    /** The directory of predefined tenant data, located in src/resources/data-tenant/ */
    String PREDEFINED_DATA_TENANT_DIR = "data-tenant/";

    String SESSION_ID = "sessionId";
    String SESSION_ID_HEADER = "X-Session-Id";

    String TOKEN = "token";
    String AUTHORIZATION = "Authorization";

    // TraceId in request header
    String X_B3_TRACEID = "X-B3-TraceId";

}
