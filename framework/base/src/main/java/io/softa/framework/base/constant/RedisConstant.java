package io.softa.framework.base.constant;

public interface RedisConstant {
    /** Expiration time in seconds */
    int ONE_MINUTES = 60;
    int FIVE_MINUTES = 60 * 5;
    int ONE_HOUR = 60 * 60;
    int ONE_DAY = 60 * 60 * 24;
    int ONE_WEEK = 60 * 60 * 24 * 7;
    int ONE_MONTH = 60 * 60 * 24 * 30;
    int ONE_QUARTER = 60 * 60 * 24 * 90;
    int ONE_YEAR = 60 * 60 * 24 * 365;

    // The default expiration time of the cache is ONE_DAY.
    int DEFAULT_EXPIRE_SECONDS = ONE_MONTH;

    /** redis key routes */
    String SESSION =  "session:";
    String USER_INFO =  "user-info:";
    String USER_PERMISSIONS =  "user-permissions:";
    String VERIFICATION_CODE =  "verification-code:";


    String TEMP_TOKEN = "temp-token:";
    String ONE_TIME_KEY = "one-time-key:";
}
