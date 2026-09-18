package com.logiplatform.service;

/**
 * Deliberately NOT the same situation as CarrierGatewayPort (airline booking).
 * Email delivery uses a standardized, universal protocol (SMTP) that any provider
 * supports — Gmail, SendGrid, AWS SES, Mailgun, a company's own mail server — with
 * no special partnership required, just host/port/credentials. That means
 * SmtpNotificationAdapter is a genuinely complete, working implementation, not a
 * permanent stand-in for something that can't be built.
 *
 * What IS still true, same as everywhere else in this codebase: it's never been
 * compiled, and actually sending mail has never been tested against a real SMTP
 * server in this sandbox (no outbound SMTP access here). The code follows the
 * standard Spring Mail / JavaMail pattern correctly as far as careful review can
 * tell, but "correct by review" still isn't "verified" — same standing caveat as
 * every other piece of Java in this project.
 *
 * LoggingNotificationAdapter is the default (active whenever SMTP isn't
 * configured) — always works, sends nothing, just records what would have gone
 * out. That's a genuinely useful audit trail on its own, not merely a placeholder.
 */
public interface NotificationSenderPort {
    NotificationResult send(String recipientEmail, String subject, String body);

    record NotificationResult(boolean sent, String errorDetail) {}
}
