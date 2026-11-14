package io.softa.starter.message.mail.validation;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.entity.PlatformMailSendServer;
import io.softa.starter.message.mail.entity.PlatformMailTemplate;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import io.softa.starter.message.mail.service.PlatformMailSendServerService;
import io.softa.starter.message.mail.service.PlatformMailTemplateService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One code, one tier; a pinned send server of the template's own owner — on every write
 * ({@link MailTemplateWriteValidator}).
 */
class MailTemplateWriteValidatorTest {

    private PlatformMailTemplateService platformTemplates;
    private MailSendServerConfigService tenantServers;
    private PlatformMailSendServerService platformServers;
    private MailTemplateWriteValidator validator;

    @BeforeEach
    void setUp() {
        platformTemplates = mock(PlatformMailTemplateService.class);
        tenantServers = mock(MailSendServerConfigService.class);
        platformServers = mock(PlatformMailSendServerService.class);
        validator = new MailTemplateWriteValidator(platformTemplates, tenantServers, platformServers);
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    @Test
    void aTenantTemplateCannotTakeAPlatformCode() {
        when(platformTemplates.findAnyByCode("user.password-reset")).thenReturn(Optional.of(new PlatformMailTemplate()));
        WriteContext ctx = WriteContext.of("MailTemplate", AccessType.CREATE, row("code", "user.password-reset"), null);

        validator.validateCreate(ctx);

        assertThat(ctx.errors()).singleElement().satisfies(e -> assertThat(e.field()).isEqualTo("code"));
    }

    @Test
    void aTenantTemplateWithItsOwnCodePasses() {
        WriteContext ctx = WriteContext.of("MailTemplate", AccessType.CREATE, row("code", "preboarding.invitation"), null);

        validator.validateCreate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void thePlatformsOwnCodesAreNotCheckedAgainstThemselves() {
        WriteContext ctx = WriteContext.of("PlatformMailTemplate", AccessType.CREATE,
                row("code", "user.password-reset"), null);

        validator.validateCreate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
        verify(platformTemplates, never()).findAnyByCode(any());
    }

    @Test
    void aTenantTemplatePinsOnlyOneOfItsTenantsServers() {
        when(tenantServers.getById(9L)).thenReturn(Optional.empty());
        WriteContext ctx = WriteContext.of("MailTemplate", AccessType.CREATE,
                row("code", "x", "preferredServerConfigId", "9"), null);

        validator.validateCreate(ctx);

        assertThat(ctx.hasErrors()).isTrue();
        verify(platformServers, never()).getById(any());
    }

    @Test
    void aTenantTemplatePinningItsOwnServerPasses() {
        when(tenantServers.getById(9L)).thenReturn(Optional.of(new MailSendServerConfig()));
        WriteContext ctx = WriteContext.of("MailTemplate", AccessType.CREATE,
                row("code", "x", "preferredServerConfigId", 9L), null);

        validator.validateCreate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void aPlatformTemplatePinsOnlyAPlatformServer() {
        when(platformServers.getById(7L)).thenReturn(Optional.of(new PlatformMailSendServer()));
        WriteContext ok = WriteContext.of("PlatformMailTemplate", AccessType.CREATE,
                row("code", "x", "preferredServerConfigId", 7L), null);
        WriteContext refused = WriteContext.of("PlatformMailTemplate", AccessType.CREATE,
                row("code", "x", "preferredServerConfigId", 8L), null);

        validator.validateCreate(ok);
        validator.validateCreate(refused);

        assertThat(ok.hasErrors()).isFalse();
        assertThat(refused.hasErrors()).isTrue();
        verify(tenantServers, never()).getById(any());
    }

    @Test
    void anUpdateThatTouchesNeitherFieldLooksNothingUp() {
        Map<String, Object> merged = row("code", "user.password-reset", "preferredServerConfigId", 9L, "name", "New");
        WriteContext ctx = WriteContext.of("MailTemplate", AccessType.UPDATE, merged, row("name", "New"), merged);

        validator.validateUpdate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
        verify(platformTemplates, never()).findAnyByCode(any());
        verify(tenantServers, never()).getById(any());
    }
}
