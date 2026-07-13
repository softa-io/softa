package io.softa.starter.message.mq.outbox;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.web.controller.EntityController;

/**
 * REST controller for transactional outbox diagnostics (read-heavy admin UI).
 * Standard CRUD is served by {@code ModelController}; this class exists for
 * OpenAPI tagging and future ops actions.
 */
@Tag(name = "OutboxEntry")
@RestController
@RequestMapping("/OutboxEntry")
public class OutboxEntryController
        extends EntityController<OutboxService, OutboxEntry, Long> {
}
