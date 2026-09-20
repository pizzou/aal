package com.logiplatform.service;

import com.logiplatform.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private static final String BREVO_URL =
            "https://api.brevo.com/v3/smtp/email";

    private final RestTemplate restTemplate;

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.mail.from:}")
    private String from;

    @Value("${app.mail.brevo-api-key:}")
    private String brevoApiKey;

    @Value("${app.frontend.url:https://portal.africalogisticaviation.com}")
    private String frontendUrl;

    public MailService() {

        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);

        this.restTemplate =
                new RestTemplate(factory);
    }

    @Async
    public void sendQuoteRequestReceived(
            String recipientEmail,
            String recipientName,
            String quoteReference,
            String route,
            String serviceType) {

        if (recipientEmail == null || recipientEmail.isBlank()) return;

        send(
                recipientEmail.trim(),
                "Aviation Africa Logistics - Quote request received " + escape(quoteReference),
                """
                <html><body style="font-family:Arial,sans-serif;color:#172033;line-height:1.55">
                  <div style="max-width:680px;margin:0 auto;padding:28px">
                    <div style="font-size:13px;font-weight:700;letter-spacing:1.5px;color:#0b5cab">AFRICA LOGISTIC AVIATION</div>
                    <h2 style="margin:10px 0 8px">We received your quote request</h2>
                    <p>Hello %s,</p>
                    <p>AAL has received your freight quotation request. Our commercial team will review the shipment details and send the quotation to this email address.</p>
                    <table style="width:100%%;border-collapse:collapse;margin:20px 0">
                      <tr><td style="padding:8px 0;color:#64748b">Reference</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                      <tr><td style="padding:8px 0;color:#64748b">Route</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                      <tr><td style="padding:8px 0;color:#64748b">Service</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                    </table>
                    <p>No account is required. When the quotation is ready, AAL will send you a secure link where you can review, accept and book the shipment.</p>
                    <p style="font-size:13px;color:#64748b">Please keep this email for your reference.</p>
                    <p>Africa Logistic Aviation</p>
                  </div>
                </body></html>
                """.formatted(
                        escape(recipientName == null || recipientName.isBlank() ? "Customer" : recipientName),
                        escape(quoteReference),
                        escape(route),
                        escape(serviceType))
        );
    }

    @Async
    public void sendPublicQuoteResult(
            String recipientEmail,
            String recipientName,
            String quoteReference,
            String route,
            java.util.List<com.logiplatform.dto.PublicCommercialDtos.PublicQuoteOption> options,
            String resultUrl) {

        if (recipientEmail == null || recipientEmail.isBlank() || resultUrl == null || resultUrl.isBlank()) return;

        StringBuilder rows = new StringBuilder();
        if (options == null || options.isEmpty()) {
            rows.append("<tr><td colspan=\"2\" style=\"padding:10px 0;color:#64748b\">No instant rate card was available. AAL will prepare a formal quotation.</td></tr>");
        } else {
            for (var option : options) {
                rows.append("<tr><td style=\"padding:8px 0;color:#64748b\">")
                        .append(escape(option.modeLabel()))
                        .append("</td><td style=\"padding:8px 0;font-weight:700\">")
                        .append(escape(option.currency()))
                        .append(" ")
                        .append(escape(option.totalCharge() == null ? "—" : option.totalCharge().stripTrailingZeros().toPlainString()))
                        .append("</td></tr>");
            }
        }

        send(
                recipientEmail.trim(),
                "Aviation Africa Logistics - Quote options " + escape(quoteReference),
                """
                <html><body style=\"font-family:Arial,sans-serif;color:#172033;line-height:1.55\">
                  <div style=\"max-width:680px;margin:0 auto;padding:28px\">
                    <div style=\"font-size:13px;font-weight:700;letter-spacing:1.5px;color:#0b5cab\">AFRICA LOGISTIC AVIATION</div>
                    <h2 style=\"margin:10px 0 8px\">Your quote options are ready</h2>
                    <p>Hello %s,</p>
                    <p>Your request <strong>%s</strong> is ready to review online.</p>
                    <p style=\"font-size:14px;color:#475569\">Route: <strong>%s</strong></p>
                    <table style=\"width:100%%;border-collapse:collapse;margin:20px 0\">
                      %s
                    </table>
                    <p><a href=\"%s\" style=\"display:inline-block;padding:13px 22px;background:#0b5cab;color:#fff;text-decoration:none;border-radius:7px;font-weight:700\">View quote options</a></p>
                    <p style=\"font-size:13px;color:#64748b\">No account is required. You can select an option and continue to booking.</p>
                    <p>Africa Logistic Aviation</p>
                  </div>
                </body></html>
                """.formatted(
                        escape(recipientName == null || recipientName.isBlank() ? "Customer" : recipientName),
                        escape(quoteReference),
                        escape(route),
                        rows.toString(),
                        escapeAttribute(resultUrl))
        );
    }

    @Async
    public void sendQuotationShare(
            String recipientEmail,
            String recipientName,
            String quoteReference,
            String quoteUrl,
            String route,
            String serviceType,
            String quotedAmount,
            String validUntil) {

        if (recipientEmail == null || recipientEmail.isBlank() || quoteUrl == null || quoteUrl.isBlank()) return;

        send(
                recipientEmail.trim(),
                "Aviation Africa Logistics - Quotation " + escape(quoteReference),
                """
                <html><body style="font-family:Arial,sans-serif;color:#172033;line-height:1.55">
                  <div style="max-width:680px;margin:0 auto;padding:28px">
                    <div style="font-size:13px;font-weight:700;letter-spacing:1.5px;color:#0b5cab">AVIATION AFRICA LOGISTICS</div>
                    <h2 style="margin:10px 0 8px">Your freight quotation is ready</h2>
                    <p>Hello %s,</p>
                    <p>AAL has prepared quotation <strong>%s</strong> for your logistics requirements.</p>
                    <table style="width:100%%;border-collapse:collapse;margin:20px 0">
                      <tr><td style="padding:8px 0;color:#64748b">Route</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                      <tr><td style="padding:8px 0;color:#64748b">Service</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                      <tr><td style="padding:8px 0;color:#64748b">Quoted total</td><td style="padding:8px 0;font-weight:700">%s</td></tr>
                      <tr><td style="padding:8px 0;color:#64748b">Valid until</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                    </table>
                    <p><a href="%s" style="display:inline-block;padding:13px 22px;background:#0b5cab;color:#fff;text-decoration:none;border-radius:7px;font-weight:700">View quotation</a></p>
                    <p style="font-size:13px;color:#64748b">You can review the quotation securely online and accept or decline it from the quotation page.</p>
                    <p>Aviation Africa Logistics</p>
                  </div>
                </body></html>
                """.formatted(
                        escape(recipientName == null || recipientName.isBlank() ? "Customer" : recipientName),
                        escape(quoteReference), escape(route), escape(serviceType), escape(quotedAmount), escape(validUntil), escapeAttribute(quoteUrl))
        );
    }

    @Async
    public void sendPublicBookingConfirmation(
            String recipientEmail,
            String recipientName,
            String bookingReference,
            String trackingToken,
            String route) {

        if (recipientEmail == null || recipientEmail.isBlank()) return;

        String trackingUrl = frontendUrl.replaceAll("/$", "") + "/track/" + trackingToken;

        send(
                recipientEmail.trim(),
                "Aviation Africa Logistics - Booking " + escape(bookingReference),
                """
                <html><body style="font-family:Arial,sans-serif;color:#172033;line-height:1.55">
                  <div style="max-width:680px;margin:0 auto;padding:28px">
                    <div style="font-size:13px;font-weight:700;letter-spacing:1.5px;color:#0b5cab">AFRICA LOGISTIC AVIATION</div>
                    <h2 style="margin:10px 0 8px">Your booking has been received</h2>
                    <p>Hello %s,</p>
                    <p>AAL has received booking <strong>%s</strong>.</p>
                    <table style="width:100%%;border-collapse:collapse;margin:20px 0">
                      <tr><td style="padding:8px 0;color:#64748b">Route</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                      <tr><td style="padding:8px 0;color:#64748b">Tracking token</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                    </table>
                    <p><a href="%s" style="display:inline-block;padding:13px 22px;background:#0b5cab;color:#fff;text-decoration:none;border-radius:7px;font-weight:700">Track shipment</a></p>
                    <p style="font-size:13px;color:#64748b">No account is required to track this shipment.</p>
                    <p>Africa Logistic Aviation</p>
                  </div>
                </body></html>
                """.formatted(
                        escape(recipientName == null || recipientName.isBlank() ? "Customer" : recipientName),
                        escape(bookingReference),
                        escape(route),
                        escape(trackingToken),
                        escapeAttribute(trackingUrl))
        );
    }

    @Async
    public void sendLoginOtp(
            User user,
            String code,
            int minutes) {

        if (user == null
                || user.getEmail() == null
                || user.getEmail().isBlank()
                || code == null
                || code.isBlank()) {
            return;
        }

        if (!mailEnabled) {
            return;
        }

        send(
                user.getEmail(),
                "Aviation Africa Logistics - Sign-in verification code",
                """
                <html>
                <body style="font-family:Arial,sans-serif;color:#172033">
                    <h2>Sign-in verification</h2>

                    <p>Hello %s,</p>

                    <p>Your Aviation Africa Logistics verification code is:</p>

                    <div style="
                        font-size:32px;
                        font-weight:700;
                        letter-spacing:8px;
                        margin:24px 0;
                        color:#0b5cab;">
                        %s
                    </div>

                    <p>This code expires in %d minutes and can only be used once.</p>

                    <p>If you did not attempt to sign in, you can ignore this email.</p>

                    <p>
                        Aviation Africa Logistics
                    </p>
                </body>
                </html>
                """.formatted(
                        escape(user.getDisplayName()),
                        escape(code),
                        minutes
                )
        );
    }

    @Async
    public void sendPasswordReset(
            User user,
            String rawToken,
            String frontendUrl) {

        if (user == null
                || user.getEmail() == null
                || user.getEmail().isBlank()
                || rawToken == null
                || rawToken.isBlank()) {
            return;
        }

        String resetUrl =
                frontendUrl
                        + "/reset-password?token="
                        + rawToken;

        send(
                user.getEmail(),
                "Aviation Africa Logistics - Password reset",
                """
                <html>
                <body style="font-family:Arial,sans-serif;color:#172033">
                    <h2>Password reset</h2>

                    <p>Hello %s,</p>

                    <p>
                        A password reset was requested for your
                        Aviation Africa Logistics account.
                    </p>

                    <p>
                        <a href="%s"
                           style="
                           display:inline-block;
                           padding:12px 20px;
                           background:#0b5cab;
                           color:#ffffff;
                           text-decoration:none;
                           border-radius:6px;">
                            Reset password
                        </a>
                    </p>

                    <p>
                        This link expires in 60 minutes.
                    </p>

                    <p>
                        If you did not request this reset,
                        you can safely ignore this email.
                    </p>
                </body>
                </html>
                """.formatted(
                        escape(user.getDisplayName()),
                        escapeAttribute(resetUrl)
                )
        );
    }

    @Async
    public void sendNewUserCredentials(
            User user,
            String temporaryPassword,
            String loginUrl) {

        if (user == null
                || user.getEmail() == null
                || user.getEmail().isBlank()) {
            return;
        }

        send(
                user.getEmail(),
                "Aviation Africa Logistics - Your account",
                """
                <html>
                <body style="font-family:Arial,sans-serif;color:#172033">
                    <h2>Your AAL account is ready</h2>

                    <p>Hello %s,</p>

                    <p>Your Aviation Africa Logistics account has been created.</p>

                    <p>
                        <strong>Email:</strong> %s<br>
                        <strong>Temporary password:</strong> %s
                    </p>

                    <p>
                        <a href="%s"
                           style="
                           display:inline-block;
                           padding:12px 20px;
                           background:#0b5cab;
                           color:#ffffff;
                           text-decoration:none;
                           border-radius:6px;">
                            Sign in
                        </a>
                    </p>

                    <p>
                        Change your temporary password immediately after signing in.
                    </p>
                </body>
                </html>
                """.formatted(
                        escape(user.getDisplayName()),
                        escape(user.getEmail()),
                        escape(temporaryPassword),
                        escapeAttribute(loginUrl)
                )
        );
    }

    private void send(
            String to,
            String subject,
            String html) {

        if (to == null || to.isBlank()) {
            return;
        }

        if (subject == null || subject.isBlank()) {
            return;
        }

        if (html == null) {
            return;
        }

        if (!mailEnabled) {
            log.debug("AAL mail delivery is disabled by configuration");
            return;
        }

        if (brevoApiKey == null
                || brevoApiKey.isBlank()) {

            log.warn("Brevo API key is not configured; email delivery is disabled");

            return;
        }

        if (from == null || from.isBlank()) {

            log.warn("Mail sender address is not configured; email delivery is disabled");

            return;
        }

        try {

            HttpHeaders headers =
                    new HttpHeaders();

            headers.setContentType(
                    MediaType.APPLICATION_JSON);

            headers.setAccept(
                    List.of(MediaType.APPLICATION_JSON));

            headers.set(
                    "api-key",
                    brevoApiKey
            );

            Map<String, Object> payload =
                    Map.of(
                            "sender",
                            Map.of(
                                    "email",
                                    from,
                                    "name",
                                    "Aviation Africa Logistics"
                            ),
                            "to",
                            List.of(
                                    Map.of(
                                            "email",
                                            to
                                    )
                            ),
                            "subject",
                            subject,
                            "htmlContent",
                            html
                    );

            ResponseEntity<String> response =
                    restTemplate.exchange(
                            BREVO_URL,
                            HttpMethod.POST,
                            new HttpEntity<>(
                                    payload,
                                    headers
                            ),
                            String.class
                    );

            if (!response.getStatusCode().is2xxSuccessful()) {

                log.warn("Brevo rejected email. httpStatus={}", response.getStatusCode().value());
            }

        } catch (Exception ex) {

            /*
             * Critical:
             * Email-provider failure must NEVER turn a valid
             * authentication request into HTTP 500.
             */
            log.error("Email delivery failed: {}", ex.getMessage(), ex);
        }
    }

    private String escape(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String escapeAttribute(String value) {

        return escape(value);
    }
}