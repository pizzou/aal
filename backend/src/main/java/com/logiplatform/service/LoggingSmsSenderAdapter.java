package com.logiplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sms.twilio.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingSmsSenderAdapter implements SmsSenderPort {
    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSenderAdapter.class);

    @Override
    public SendResult send(String recipient, String message) {
        log.info("SMS provider not configured; recipient={} messageLength={}", recipient, message == null ? 0 : message.length());
        return new SendResult(false, "LOGGING", null, "SMS provider not configured");
    }
}
