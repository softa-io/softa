package io.softa.framework.base.enums;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OperatorTest {

    @Test()
    void ofEqual() {
        Operator operator = Operator.of("=");
        Assertions.assertEquals(Operator.EQUAL, operator);
    }

    @Test()
    void ofNotContains() {
        Operator operator = Operator.of("NOT CONTAINS");
        Assertions.assertEquals(Operator.NOT_CONTAINS, operator);
    }

    @Test()
    void ofBetween() {
        Operator operator = Operator.of("BETWEEN");
        Assertions.assertEquals(Operator.BETWEEN, operator);
    }

    @Test()
    void ofStartWith() {
        Operator operator = Operator.of("Start with");
        Assertions.assertEquals(Operator.START_WITH, operator);
    }

    @Test()
    void ofIsSet() {
        Operator operator = Operator.of("is set");
        Assertions.assertEquals(Operator.IS_SET, operator);
    }

    @Test()
    void ofParentOf() {
        Operator operator = Operator.of("PARENT OF");
        Assertions.assertEquals(Operator.PARENT_OF, operator);
    }

}