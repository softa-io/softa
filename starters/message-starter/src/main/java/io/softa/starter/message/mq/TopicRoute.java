package io.softa.starter.message.mq;

/**
 * Logical topic identifier resolved to a physical topic name via
 * {@link MqTopicsProperties}. Held as an enum so call sites cannot typo
 * a topic string.
 */
public enum TopicRoute {
    MAIL_SEND,
    SMS_SEND
}
