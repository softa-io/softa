package io.softa.framework.web.session;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * What a session stands for: one account (one membership of one tenant), the person behind it, and
 * the client it was issued to.
 *
 * <p>Stored as the value of {@code session:{sessionId}}. The person is kept beside the account so a
 * revoked session can still be attributed (login history, device clean-up) after the account has
 * been changed or unbound.
 *
 * @param accountId  the account the session enters
 * @param profileId  the person who owns that account when the session was issued
 * @param tenantId   the account's tenant
 * @param platform   {@code ios} / {@code android} for the APP, null for Web and H5
 * @param appVersion APP version from {@code X-App-Version}, null for Web and H5
 * @param deviceModel device model from {@code X-Device-Model}, null when the client does not send it
 * @param issuedAt   when the session was issued
 */
public record SessionRecord(Long accountId, Long profileId, Long tenantId,
                            String platform, String appVersion, String deviceModel,
                            LocalDateTime issuedAt) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
