package io.softa.starter.es.controller;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The change log is always masked. Its endpoints are open to every user who may read the record, so
 * whether masked values are shown is not a choice a request makes.
 */
class ChangeLogControllerMaskTest {

    @Test
    void noEndpointLetsTheRequestTurnMaskingOff() {
        for (Method method : ChangeLogController.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                RequestParam param = parameter.getAnnotation(RequestParam.class);
                String name = param == null ? parameter.getName()
                        : (!param.name().isEmpty() ? param.name() : !param.value().isEmpty() ? param.value() : parameter.getName());
                assertThat(name).as("%s takes a masking switch", method.getName()).isNotEqualToIgnoringCase("dataMask");
            }
        }
    }
}
