package io.softa.starter.metadata.seed;

import java.util.List;

/**
 * What tracing one tenant's seed data would do, before it runs.
 *
 * @param tenantId           tenant id
 * @param code               tenant code
 * @param name               tenant name
 * @param untracedBindings   bindings that do not record their file yet
 * @param filesNeverHad      files the tenant is due and has no row from — loaded whole
 * @param rowsWithoutBinding rows of the files it is due, nested ones included, that the tenant has no binding
 *                           for — each claimed when the tenant has one under the same business key, created
 *                           otherwise, and skipped when nested under a row the tenant already has
 */
public record TenantTracePreview(Long tenantId, String code, String name, long untracedBindings,
                                 List<String> filesNeverHad, int rowsWithoutBinding) {
}
