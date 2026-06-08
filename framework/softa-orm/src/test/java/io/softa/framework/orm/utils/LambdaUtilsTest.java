package io.softa.framework.orm.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.meta.MetaModel;

class LambdaUtilsTest {

    @Test
    void getAttributeName() {
        String attribute = LambdaUtils.getAttributeName(MetaModel::getLabel);
        Assertions.assertEquals("label", attribute);
    }

}