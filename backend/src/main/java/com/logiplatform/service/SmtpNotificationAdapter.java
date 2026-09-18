package com.logiplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Active only when notifications.smtp.enabled=true — meaning someone has
 * deliberately configured real SMTP settings (spring.mail.host / username /
 * password, typically via environment variables, never committed). Spring Boot's
 * auto-configured JavaMailSender handles the actual SMTP protocol work; this class
 * just adapts it to NotificationSenderPort.
 *
 * See NotificationSenderPort's Javadoc for why this is a genuinely complete
 * implementation (SMTP is a universal protocol) rather than another mock — and for
 * the standing caveat that it's never been compiled or tested against a real SMTP
 * server in this sandbox.
 */
@Component
@ConditionalOnProperty(name = "notifications.smtp.enabled", havingValue = "true")
public class SmtpNotificationAdapter implements NotificationSenderPort {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpNotificationAdapter(JavaMailSender mailSender,
                                    @Value("${notifications.from-address:no-reply@example.com}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public NotificationResult send(String recipientEmail, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(recipientEmail);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            return new NotificationResult(true, null);
        } catch (MailException e) {
            return new NotificationResult(false, e.getMessage());
        }
    }
}
