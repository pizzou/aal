package com.logiplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Real transactional notification channel backed by Brevo SMTP API.
 * The OTP and operational notification paths therefore share one verified
 * sender identity without exposing provider credentials to the frontend.
 */
@Component
@ConditionalOnProperty(name = "notifications.brevo.enabled", havingValue = "true")
public final class BrevoNotificationAdapter implements NotificationSenderPort {

    private static final Logger log = LoggerFactory.getLogger(BrevoNotificationAdapter.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final BrevoSenderResolver senderResolver;
    private final String apiKey;
    private final String apiUrl;
    private final String senderName;
    private final boolean mailEnabled;

    public BrevoNotificationAdapter(
            ObjectMapper objectMapper,
            BrevoSenderResolver senderResolver,
            @Value("${app.mail.brevo-api-key:}") String apiKey,
            @Value("${app.mail.brevo-url:https://api.brevo.com/v3/smtp/email}") String apiUrl,
            @Value("${app.mail.sender-name:Aviation Africa Logistics Ltd}") String senderName,
            @Value("${app.mail.enabled:false}") boolean mailEnabled) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = objectMapper;
        this.senderResolver = senderResolver;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
        this.senderName = senderName == null ? "Aviation Africa Logistics Ltd" : senderName.trim();
        this.mailEnabled = mailEnabled;
    }

    @Override
    public NotificationResult send(String recipientEmail, String subject, String body) {
        if (!mailEnabled) {
            return new NotificationResult(false, "Transactional email is disabled");
        }
        if (apiKey.isBlank()) {
            return new NotificationResult(false, "Brevo API key is not configured");
        }
        if (apiUrl.isBlank() || !apiUrl.startsWith("https://")) {
            return new NotificationResult(false, "Brevo API URL must use HTTPS");
        }
        if (recipientEmail == null || recipientEmail.isBlank()) {
            return new NotificationResult(false, "Notification recipient is missing");
        }

        String sender = senderResolver.resolveOrBlank();
        if (sender.isBlank()) {
            return new NotificationResult(false, "No verified Brevo sender is available");
        }

        Map<String, Object> payload = Map.of(
                "sender", Map.of("email", sender, "name", senderName),
                "to", List.of(Map.of("email", recipientEmail.trim())),
                "subject", subject == null ? "AAL notification" : subject,
                "htmlContent", textToHtml(body == null ? "" : body));

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("api-key", apiKey);

            HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return new NotificationResult(true, null);
            }
            return new NotificationResult(false, "Brevo returned HTTP " + response.getStatusCode().value());
        } catch (JsonProcessingException ex) {
            return new NotificationResult(false, "Unable to serialize Brevo notification payload");
        } catch (RestClientException ex) {
            log.warn("Brevo operational notification failed type={}", ex.getClass().getSimpleName());
            return new NotificationResult(false, "Brevo delivery failed: " + ex.getClass().getSimpleName());
        }
    }

    private static String textToHtml(String body) {
        String escaped = body
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
        return "<html><body style=\"font-family:Arial,sans-serif;line-height:1.55;color:#172033\">"
                + escaped.replace("\r\n", "\n").replace("\n", "<br>")
                + "</body></html>";
    }
}
