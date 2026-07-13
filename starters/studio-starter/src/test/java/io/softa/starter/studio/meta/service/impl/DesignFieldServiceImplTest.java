package io.softa.starter.studio.meta.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.WidgetType;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.entity.DesignFieldDomain;
import io.softa.starter.studio.meta.service.DesignFieldDomainService;

/**
 * {@code applyDomain} fills a field's type + defaults from a {@code DesignFieldDomain} as
 * a <b>one-time template</b> (not a live binding) and records {@code domainId} as design-time provenance.
 */
class DesignFieldServiceImplTest {

    @Test
    @DisplayName("applyDomain copies the domain's type + defaults onto the field and records domainId")
    void applyDomainCopiesAttrsAndRecordsProvenance() {
        DesignFieldDomainService domainService = mock(DesignFieldDomainService.class);
        DesignFieldServiceImpl service = Mockito.spy(new DesignFieldServiceImpl(domainService));

        DesignField field = new DesignField();
        field.setId(1L);
        field.setFieldType(FieldType.STRING);   // pre-existing raw type — replaced by the domain's
        doReturn(Optional.of(field)).when(service).getById(1L);
        doReturn(true).when(service).updateOne(any(DesignField.class));

        DesignFieldDomain domain = new DesignFieldDomain();
        domain.setId(50L);
        domain.setName("Money");
        domain.setFieldType(FieldType.BIG_DECIMAL);
        domain.setLength(18);
        domain.setScale(2);
        domain.setDefaultValue("0");
        domain.setWidgetType(WidgetType.TEXT);
        when(domainService.getById(50L)).thenReturn(Optional.of(domain));

        DesignField result = service.applyDomain(1L, 50L);

        assertEquals(FieldType.BIG_DECIMAL, result.getFieldType());
        assertEquals(18, result.getLength());
        assertEquals(2, result.getScale());
        assertEquals("0", result.getDefaultValue());
        assertEquals(WidgetType.TEXT, result.getWidgetType());
        assertEquals(50L, result.getDomainId(), "domainId recorded as design-time provenance");
        verify(service).updateOne(field);
    }
}
