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

import java.util.List;
import java.util.Map;

@Service
public class MailService {

    private static final String BREVO_URL =
            "https://api.brevo.com/v3/smtp/email";

    private final RestTemplate restTemplate;

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.mail.from:}")
    private String from;

    @Value("${app.mail.brevo-api-key:}")
    private String brevoApiKey;

    public MailService() {

        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);

        this.restTemplate =
                new RestTemplate(factory);
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

        if (brevoApiKey == null
                || brevoApiKey.isBlank()) {

            System.err.println(
                    "[AAL EMAIL] BREVO API key is not configured"
            );

            return;
        }

        if (from == null || from.isBlank()) {

            System.err.println(
                    "[AAL EMAIL] Mail sender address is not configured"
            );

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

                System.err.println(
                        "[AAL EMAIL] Brevo rejected message. HTTP="
                                + response.getStatusCode().value()
                );
            }

        } catch (Exception ex) {

            /*
             * Critical:
             * Email-provider failure must NEVER turn a valid
             * authentication request into HTTP 500.
             */
            System.err.println(
                    "[AAL EMAIL] Delivery failed: "
                            + ex.getMessage()
            );
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