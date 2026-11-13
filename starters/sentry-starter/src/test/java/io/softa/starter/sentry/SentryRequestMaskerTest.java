package io.softa.starter.sentry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryTransaction;
import io.sentry.protocol.TransactionInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request Sentry attaches to an event or a transaction leaves the application without the credentials it
 * arrived with: those in the query string, softa's own credential headers, and a captured body.
 */
class SentryRequestMaskerTest {

    private final SentryRequestMasker masker = new SentryRequestMasker();

    /** An error event of an APP request to a signing link: no link token, no APP session, no RPC secret. */
    @Test
    void masksTheRequestOfAnEvent() {
        Request scopeRequest = signingLinkRequest();
        SentryEvent event = new SentryEvent();
        event.setRequest(scopeRequest);

        Request sent = masker.process(event, new Hint()).getRequest();

        assertThat(sent.getQueryString()).isEqualTo("token=***&verificationToken=***&documentId=42");
        assertThat(sent.getHeaders())
                .containsEntry("X-Session-Id", "***")
                .containsEntry("x-api-secret", "***")
                .containsEntry("X-Api-Key", "***")
                .containsEntry("User-Agent", "HCM/3.2 (iOS)");
        assertThat(sent.getData()).isEqualTo("{\"identifier\":\"amy@example.com\",\"password\":\"***\"}");
        assertThat(sent.getUrl()).isEqualTo(scopeRequest.getUrl());
        // The scope's request, which every event of the request shares, stays as it was.
        assertThat(scopeRequest.getQueryString()).contains("lnk-123");
        assertThat(scopeRequest.getHeaders()).containsEntry("X-Session-Id", "app-session-1");
    }

    /** A sampled transaction carries the same request and is masked the same way. */
    @Test
    void masksTheRequestOfATransaction() {
        SentryTransaction transaction = new SentryTransaction("GET /corehr/document/signing/context", 0d, 1d,
                new ArrayList<>(), new HashMap<>(), new TransactionInfo("route"));
        transaction.setRequest(signingLinkRequest());

        Request sent = masker.process(transaction, new Hint()).getRequest();

        assertThat(sent.getQueryString()).isEqualTo("token=***&verificationToken=***&documentId=42");
        assertThat(sent.getHeaders()).containsEntry("X-Session-Id", "***");
    }

    /** An event raised outside a request, by a scheduler say, has no request and passes through. */
    @Test
    void passesAnEventWithoutRequest() {
        assertThat(masker.process(new SentryEvent(), new Hint()).getRequest()).isNull();
    }

    private static Request signingLinkRequest() {
        Request request = new Request();
        request.setUrl("https://hcm.example.com/api/corehr/document/signing/context");
        request.setQueryString("token=lnk-123&verificationToken=vt-456&documentId=42");
        request.setHeaders(Map.of(
                "X-Session-Id", "app-session-1",
                "x-api-secret", "rpc-secret",
                "X-Api-Key", "rpc-key",
                "User-Agent", "HCM/3.2 (iOS)"));
        request.setData("{\"identifier\":\"amy@example.com\",\"password\":\"Pa55-word!\"}");
        return request;
    }
}
