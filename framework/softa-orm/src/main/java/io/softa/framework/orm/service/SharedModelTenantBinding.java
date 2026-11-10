package io.softa.framework.orm.service;

import java.util.List;

/**
 * Binds a shared (non-multi-tenant) model to the tenants that use it: inside a customer tenant, its rows
 * are the ones a record of that tenant points at through {@code referencingFields} of
 * {@code referencingModel}, and no others.
 *
 * <p>A shared model has no tenant column, so tenant isolation never narrows it. That is right for a
 * catalogue every tenant reads in full — currencies, banks, countries — and wrong for a model whose rows
 * belong to people of particular tenants: a person's profile is one row however many tenants employ
 * them, and read from inside one tenant it is that tenant's people only. Declared as a Spring bean by
 * the module that owns the model; the permission layer applies it to every read inside a customer
 * tenant, a tenant admin's included. The platform administrator and system work keep the whole table.
 *
 * @param model             the shared model being bound
 * @param referencingModel  a multi-tenant model whose rows name the shared model's rows
 * @param referencingFields to-one fields of {@code referencingModel} holding the shared model's id
 */
public record SharedModelTenantBinding(String model, String referencingModel, List<String> referencingFields) {

    public SharedModelTenantBinding {
        referencingFields = referencingFields == null ? List.of() : List.copyOf(referencingFields);
    }
}
