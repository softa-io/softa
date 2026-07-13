package io.softa.starter.studio.meta.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.service.DesignFieldService;
import io.softa.starter.studio.meta.entity.DesignFieldDomain;
import io.softa.starter.studio.meta.service.DesignFieldDomainService;

/**
 * DesignField Model Service Implementation.
 */
@Service
public class DesignFieldServiceImpl extends EntityServiceImpl<DesignField, Long> implements DesignFieldService {

    private final DesignFieldDomainService fieldDomainService;

    public DesignFieldServiceImpl(DesignFieldDomainService fieldDomainService) {
        this.fieldDomainService = fieldDomainService;
    }

    @Override
    public DesignField applyDomain(Long fieldId, Long domainId) {
        DesignField field = this.getById(fieldId)
                .orElseThrow(() -> new IllegalArgumentException("Field does not exist! {0}", fieldId));
        DesignFieldDomain domain = fieldDomainService.getById(domainId)
                .orElseThrow(() -> new IllegalArgumentException("Field domain does not exist! {0}", domainId));
        Assert.notNull(domain.getFieldType(), "Domain {0} has no fieldType to apply.", domainId);

        // One-time copy: the domain's type + defaults become the field's OWN attributes (a template fill,
        // not a live binding). domainId is recorded as design-time provenance only — the converge engine
        // never sees it (not a sys_field attr).
        field.setFieldType(domain.getFieldType());
        field.setLength(domain.getLength());
        field.setScale(domain.getScale());
        field.setDefaultValue(domain.getDefaultValue());
        field.setWidgetType(domain.getWidgetType());
        field.setDomainId(domainId);
        this.updateOne(field);
        return field;
    }
}
