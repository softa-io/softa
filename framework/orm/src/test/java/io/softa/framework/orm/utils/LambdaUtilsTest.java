package io.softa.framework.orm.utils;

import io.softa.framework.orm.meta.MetaModel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LambdaUtilsTest {

    @Test
    void getAttributeName() {
        String attribute = LambdaUtils.getAttributeName(MetaModel::getLabelName);
        Assertions.assertEquals("labelName", attribute);
    }

}