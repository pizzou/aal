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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpStatusCodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);


    private final RestTemplate restTemplate;

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.mail.from:}")
    private String from;

    @Value("${app.mail.brevo-api-key:}")
    private String brevoApiKey;

    @Value("${app.mail.brevo-url:https://api.brevo.com/v3/smtp/email}")
    private String brevoUrl;

    @Value("${app.mail.sender-name:Aviation Africa Logistics Ltd}")
    private String senderName;

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
                "Aviation Africa Logistics Ltd - Quote request received " + escape(quoteReference),
                """
                <html><body style="font-family:Arial,sans-serif;color:#172033;line-height:1.55">
                  <div style="max-width:680px;margin:0 auto;padding:28px">
                    <div style="font-size:13px;font-weight:700;letter-spacing:1.5px;color:#0B3B8F">AFRICA LOGISTIC AVIATION</div>
                    <h2 style="margin:10px 0 8px">We received your quote request</h2>
                    <p>Hello {{DISPLAY_NAME}},</p>
                    <p>AAL has received your freight quotation request. Our commercial team will review the shipment details and send the quotation to this email address.</p>
                    <table style="width:100%%;border-collapse:collapse;margin:20px 0">
                      <tr><td style="padding:8px 0;color:#64748b">Reference</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                      <tr><td style="padding:8px 0;color:#64748b">Route</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                      <tr><td style="padding:8px 0;color:#64748b">Service</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                    </table>
                    <p>No account is required. When the quotation is ready, AAL will send you a secure link where you can review, accept and book the shipment.</p>
                    <p style="font-size:13px;color:#64748b">Please keep this email for your reference.</p>
                    <p>Aviation Africa Logistics Ltd</p>
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
                "Aviation Africa Logistics Ltd - Quote options " + escape(quoteReference),
                """
                <html><body style=\"font-family:Arial,sans-serif;color:#172033;line-height:1.55\">
                  <div style=\"max-width:680px;margin:0 auto;padding:28px\">
                    <div style=\"font-size:13px;font-weight:700;letter-spacing:1.5px;color:#0B3B8F\">AFRICA LOGISTIC AVIATION</div>
                    <h2 style=\"margin:10px 0 8px\">Your quote options are ready</h2>
                    <p>Hello %s,</p>
                    <p>Your request <strong>%s</strong> is ready to review online.</p>
                    <p style=\"font-size:14px;color:#475569\">Route: <strong>%s</strong></p>
                    <table style=\"width:100%%;border-collapse:collapse;margin:20px 0\">
                      %s
                    </table>
                    <p><a href=\"%s\" style=\"display:inline-block;padding:13px 22px;background:#0B3B8F;color:#fff;text-decoration:none;border-radius:7px;font-weight:700\">View quote options</a></p>
                    <p style=\"font-size:13px;color:#64748b\">No account is required. You can select an option and continue to booking.</p>
                    <p>Aviation Africa Logistics Ltd</p>
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
                "Aviation Africa Logistics Ltd - Quotation " + escape(quoteReference),
                """
                <html><body style="font-family:Arial,sans-serif;color:#172033;line-height:1.55">
                  <div style="max-width:680px;margin:0 auto;padding:28px">
                    <div style="font-size:13px;font-weight:700;letter-spacing:1.5px;color:#0B3B8F">AVIATION AFRICA LOGISTICS</div>
                    <h2 style="margin:10px 0 8px">Your freight quotation is ready</h2>
                    <p>Hello %s,</p>
                    <p>AAL has prepared quotation <strong>%s</strong> for your logistics requirements.</p>
                    <table style="width:100%%;border-collapse:collapse;margin:20px 0">
                      <tr><td style="padding:8px 0;color:#64748b">Route</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                      <tr><td style="padding:8px 0;color:#64748b">Service</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                      <tr><td style="padding:8px 0;color:#64748b">Quoted total</td><td style="padding:8px 0;font-weight:700">%s</td></tr>
                      <tr><td style="padding:8px 0;color:#64748b">Valid until</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                    </table>
                    <p><a href="%s" style="display:inline-block;padding:13px 22px;background:#0B3B8F;color:#fff;text-decoration:none;border-radius:7px;font-weight:700">View quotation</a></p>
                    <p style="font-size:13px;color:#64748b">You can review the quotation securely online and accept or decline it from the quotation page.</p>
                    <p>Aviation Africa Logistics Ltd</p>
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
                "Aviation Africa Logistics Ltd - Booking " + escape(bookingReference),
                """
                <html><body style="font-family:Arial,sans-serif;color:#172033;line-height:1.55">
                  <div style="max-width:680px;margin:0 auto;padding:28px">
                    <div style="font-size:13px;font-weight:700;letter-spacing:1.5px;color:#0B3B8F">AFRICA LOGISTIC AVIATION</div>
                    <h2 style="margin:10px 0 8px">Your booking has been received</h2>
                    <p>Hello %s,</p>
                    <p>AAL has received booking <strong>%s</strong>.</p>
                    <table style="width:100%%;border-collapse:collapse;margin:20px 0">
                      <tr><td style="padding:8px 0;color:#64748b">Route</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                      <tr><td style="padding:8px 0;color:#64748b">Tracking token</td><td style="padding:8px 0;font-weight:600">%s</td></tr>
                    </table>
                    <p><a href="%s" style="display:inline-block;padding:13px 22px;background:#0B3B8F;color:#fff;text-decoration:none;border-radius:7px;font-weight:700">Track shipment</a></p>
                    <p style="font-size:13px;color:#64748b">No account is required to track this shipment.</p>
                    <p>Aviation Africa Logistics Ltd</p>
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

    /**
     * Sends the authentication OTP synchronously. Authentication must not
     * report an OTP challenge as successfully issued while the provider has
     * actually rejected the message.
     */
    public void sendLoginOtp(
            User user,
            String code,
            int minutes) {

        if (user == null
                || user.getEmail() == null
                || user.getEmail().isBlank()
                || code == null
                || !code.matches("\\d{6}")) {
            throw new MailDeliveryException("Invalid OTP email request");
        }

        if (!mailEnabled) {
            throw new MailDeliveryException("Transactional email is disabled");
        }

        if (brevoApiKey == null || brevoApiKey.isBlank()) {
            throw new MailDeliveryException("Transactional email provider is not configured");
        }

        if (from == null || from.isBlank()) {
            throw new MailDeliveryException("Transactional email sender is not configured");
        }

        sendOrThrow(
                user.getEmail().trim(),
                "Aviation Africa Logistics Ltd - Sign-in verification code",
                """
                <html>
                <body style="margin:0;background:#f5f8ff;font-family:Arial,sans-serif;color:#172033">
                  <div style="max-width:620px;margin:0 auto;padding:32px 18px">
                    <div style="background:#071A52;border-radius:18px 18px 0 0;padding:22px 24px;color:#ffffff">
                      <div style="font-size:12px;font-weight:800;letter-spacing:1.8px">AVIATION AFRICA LOGISTICS LTD</div>
                      <div style="margin-top:5px;font-size:9px;color:#FFD21F;font-weight:800;letter-spacing:1.5px">GLOBAL REACH · AFRICAN ROOTS</div>
                    </div>
                    <div style="background:#ffffff;border:1px solid #e1e7f0;border-top:0;border-radius:0 0 18px 18px;padding:30px 24px">
                      <div style="font-size:11px;font-weight:800;letter-spacing:1.4px;color:#1769D8">SECURE SIGN-IN</div>
                      <h2 style="margin:9px 0 8px;color:#071A52">Your verification code</h2>
                      <p>Hello {{DISPLAY_NAME}},</p>
                      <p>Your Aviation Africa Logistics Ltd sign-in verification code is:</p>
                      <div style="font-size:34px;font-weight:800;letter-spacing:9px;text-align:center;margin:26px 0;padding:18px;border-radius:14px;background:#f5f8ff;color:#071A52;border:1px solid #dce6f5">{{OTP_CODE}}</div>
                      <p>This code expires in {{OTP_MINUTES}} minutes and can only be used once.</p>
                      <p style="font-size:13px;color:#667085">If you did not attempt to sign in, no action is required.</p>
                      <div style="height:3px;margin-top:24px;background:linear-gradient(90deg,#1769D8 0 45%,#FFD21F 45% 70%,#ED1C24 70% 100%)"></div>
                      <p style="font-size:12px;color:#667085">Aviation Africa Logistics Ltd · Secure Operations Platform</p>
                    </div>
                  </div>
                </body>
                </html>
                """
                        .replace("{{DISPLAY_NAME}}", escape(user.getDisplayName()))
                        .replace("{{OTP_CODE}}", escape(code))
                        .replace("{{OTP_MINUTES}}", Integer.toString(minutes))
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
                "Aviation Africa Logistics Ltd - Password reset",
                """
                <html>
                <body style="font-family:Arial,sans-serif;color:#172033">
                    <h2>Password reset</h2>

                    <p>Hello %s,</p>

                    <p>
                        A password reset was requested for your
                        Aviation Africa Logistics Ltd account.
                    </p>

                    <p>
                        <a href="%s"
                           style="
                           display:inline-block;
                           padding:12px 20px;
                           background:#0B3B8F;
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
                "Aviation Africa Logistics Ltd - Your account",
                """
                <html>
                <body style="font-family:Arial,sans-serif;color:#172033">
                    <h2>Your AAL account is ready</h2>

                    <p>Hello %s,</p>

                    <p>Your Aviation Africa Logistics Ltd account has been created.</p>

                    <p>
                        <strong>Email:</strong> %s<br>
                        <strong>Temporary password:</strong> %s
                    </p>

                    <p>
                        <a href="%s"
                           style="
                           display:inline-block;
                           padding:12px 20px;
                           background:#0B3B8F;
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

        if (to == null || to.isBlank() || subject == null || subject.isBlank() || html == null) {
            return;
        }

        if (!mailEnabled) {
            log.debug("AAL mail delivery is disabled by configuration");
            return;
        }

        if (brevoApiKey == null || brevoApiKey.isBlank()) {
            MailDeliveryException ex =
                    new MailDeliveryException("Transactional email provider is not configured");
            log.error("Email delivery unavailable reason={}", ex.getMessage());
            throw ex;
        }

        if (from == null || from.isBlank()) {
            MailDeliveryException ex =
                    new MailDeliveryException("Transactional email sender is not configured");
            log.error("Email delivery unavailable reason={}", ex.getMessage());
            throw ex;
        }

        try {
            sendOrThrow(to, subject, html);
        } catch (MailDeliveryException ex) {
            log.error("Email delivery failed reason={}", ex.getMessage());
            throw ex;
        }
    }

    private void sendOrThrow(
            String to,
            String subject,
            String html) {

        if (brevoUrl == null || brevoUrl.isBlank()) {
            throw new MailDeliveryException("Transactional email provider URL is not configured");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("api-key", brevoApiKey);

        Map<String, Object> payload = Map.of(
                "sender", Map.of(
                        "email", from,
                        "name", senderName == null || senderName.isBlank()
                                ? "Aviation Africa Logistics Ltd"
                                : senderName.trim()),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "htmlContent", html);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    brevoUrl.trim(),
                    HttpMethod.POST,
                    new HttpEntity<>(payload, headers),
                    String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                String providerDetail = summarizeProviderResponse(response.getBody());
                log.error(
                        "Brevo rejected email httpStatus={} sender={} recipient={} detail={}",
                        response.getStatusCode().value(),
                        maskEmail(from),
                        maskEmail(to),
                        providerDetail);
                throw new MailDeliveryException(
                        "Email provider rejected the message (HTTP " + response.getStatusCode().value() + ")",
                        null);
            }

            log.info(
                    "Transactional email accepted by Brevo recipient={} sender={} providerResponse={}",
                    maskEmail(to),
                    maskEmail(from),
                    summarizeProviderResponse(response.getBody()));
        } catch (HttpStatusCodeException ex) {
            String providerDetail = summarizeProviderResponse(ex.getResponseBodyAsString());
            log.error(
                    "Brevo rejected email httpStatus={} sender={} recipient={} detail={}",
                    ex.getStatusCode().value(),
                    maskEmail(from),
                    maskEmail(to),
                    providerDetail);
            throw new MailDeliveryException(
                    "Email provider rejected the message (HTTP " + ex.getStatusCode().value() + ")", ex);
        } catch (RestClientException ex) {
            log.error("Email provider request failed type={}", ex.getClass().getSimpleName());
            throw new MailDeliveryException("Email provider is temporarily unavailable", ex);
        }
    }

    private String summarizeProviderResponse(String body) {
        if (body == null || body.isBlank()) {
            return "no-provider-detail";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 1000) {
            return compact.substring(0, 1000) + "...";
        }
        return compact;
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return "unknown";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }

    public static final class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message) {
            super(message);
        }

        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
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