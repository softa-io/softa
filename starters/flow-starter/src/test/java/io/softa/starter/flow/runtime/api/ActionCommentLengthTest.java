package io.softa.starter.flow.runtime.api;

import java.util.Set;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An approver's comment — a rejection's or a return's reason above all — is at most 256 characters, as a batch
 * action's is: a longer one was accepted and stored whole.
 */
class ActionCommentLengthTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @Test
    void aCommentOfMoreThan256CharactersIsRefused() {
        FlowRejectRequest reject = new FlowRejectRequest();
        reject.setInstanceId("i1");
        reject.setNodeId("manager");
        reject.setComment("x".repeat(257));

        Set<ConstraintViolation<FlowRejectRequest>> violations = VALIDATOR.validate(reject);

        assertEquals(1, violations.size());
        ConstraintViolation<FlowRejectRequest> violation = violations.iterator().next();
        assertEquals("comment", violation.getPropertyPath().toString());
        assertEquals("The comment can be at most 256 characters", violation.getMessage());
    }

    @Test
    void aCommentOf256CharactersIsAccepted() {
        FlowReturnRequest returned = new FlowReturnRequest();
        returned.setInstanceId("i1");
        returned.setComment("x".repeat(256));

        assertTrue(VALIDATOR.validate(returned).stream()
                .noneMatch(violation -> "comment".equals(violation.getPropertyPath().toString())));
    }
}
