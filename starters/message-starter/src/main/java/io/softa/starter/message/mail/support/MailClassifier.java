package io.softa.starter.message.mail.support;

import java.util.List;
import java.util.Optional;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.classifier.MailClassificationRule;
import io.softa.starter.message.mail.classifier.MailClassificationSupport;
import io.softa.starter.message.mail.classifier.MailFlagDetector;

/**
 * Two-phase classifier:
 * <ol>
 *   <li>A chain-of-responsibility over {@link MailClassificationRule}s
 *       resolves the mutually-exclusive primary content type
 *       (READ_RECEIPT / BOUNCE / AUTO_REPLY / CALENDAR_INVITE / NORMAL /
 *       UNKNOWN). Rules are tried in {@link org.springframework.core.annotation.Order}
 *       sequence; the first non-empty match wins.</li>
 *   <li>A parallel pass over {@link MailFlagDetector}s annotates the result
 *       with orthogonal boolean flags (mailing-list, encrypted, spam).
 *       Detectors are independent — each contributes its own flag without
 *       short-circuiting the others.</li>
 * </ol>
 * Extension points:
 * <ul>
 *   <li>New content type → register a {@code MailClassificationRule}
 *       {@code @Component} and add a value to {@code ReceivedMailType}.</li>
 *   <li>New orthogonal property → register a {@code MailFlagDetector} and
 *       add the corresponding boolean field to {@code MailClassification}
 *       and {@code MailReceiveRecord}.</li>
 * </ul>
 */
@Slf4j
@Component
public class MailClassifier {

    @Autowired
    private List<MailClassificationRule> rules;

    @Autowired
    private List<MailFlagDetector> flagDetectors;

    public MailClassification classify(MimeMessage msg) {
        MailClassification result = classifyPrimaryType(msg);
        applyFlags(msg, result);
        return result;
    }

    private MailClassification classifyPrimaryType(MimeMessage msg) {
        boolean anyRuleThrew = false;
        for (MailClassificationRule rule : rules) {
            try {
                Optional<MailClassification> match = rule.match(msg);
                if (match.isPresent()) return match.get();
            } catch (Exception e) {
                anyRuleThrew = true;
                log.warn("MailClassifier: rule {} threw on Message-ID={}: {}",
                        rule.getClass().getSimpleName(),
                        MailClassificationSupport.safeMessageId(msg),
                        e.getMessage());
            }
        }
        // No specialty rule matched. Distinguish "all rules ran cleanly"
        // (NORMAL) from "some rules failed during scan" (UNKNOWN) so that ops
        // can spot classifier regressions without misclassifying clean
        // correspondence.
        return anyRuleThrew
                ? MailClassification.unknown()
                : MailClassification.normal();
    }

    private void applyFlags(MimeMessage msg, MailClassification result) {
        for (MailFlagDetector detector : flagDetectors) {
            try {
                detector.apply(msg, result);
            } catch (Exception e) {
                log.warn("MailClassifier: flag detector {} threw on Message-ID={}: {}",
                        detector.getClass().getSimpleName(),
                        MailClassificationSupport.safeMessageId(msg),
                        e.getMessage());
            }
        }
    }
}
