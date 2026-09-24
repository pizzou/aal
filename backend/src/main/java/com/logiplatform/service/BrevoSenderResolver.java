package com.logiplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.ParameterizedTypeReference;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves the sender that AAL should use for Brevo transactional email.
 *
 * Resolution order:
 * 1. Explicit AAL_BREVO_SENDER_EMAIL / NOTIFICATIONS_FROM_ADDRESS.
 * 2. A verified active sender from Brevo whose name matches the configured
 *    AAL sender name.
 * 3. The only active Brevo sender when the account has exactly one.
 *
 * The discovery result is cached for a short period so OTP requests do not
 * call Brevo's sender registry for every login.
 */
@Service
public class BrevoSenderResolver {

    private static final Logger log =
            LoggerFactory.getLogger(BrevoSenderResolver.class);

    private static final Pattern EMAIL =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final Duration CACHE_TTL =
            Duration.ofMinutes(10);

    private final RestTemplate restTemplate;
    private final String explicitSender;
    private final String notificationSender;
    private final String apiKey;
    private final String sendersUrl;
    private final String senderName;

    private volatile String cachedSender;
    private volatile Instant cachedAt;

    public BrevoSenderResolver(
            @Value("${app.mail.from:}") String explicitSender,
            @Value("${notifications.from-address:}") String notificationSender,
            @Value("${app.mail.brevo-api-key:}") String apiKey,
            @Value("${app.mail.brevo-senders-url:https://api.brevo.com/v3/senders}") String sendersUrl,
            @Value("${app.mail.sender-name:Aviation Africa Logistics Ltd}") String senderName) {

        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);

        this.restTemplate = new RestTemplate(factory);
        this.explicitSender = normalize(explicitSender);
        this.notificationSender = normalize(notificationSender);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.sendersUrl = sendersUrl == null ? "" : sendersUrl.trim();
        this.senderName = senderName == null ? "" : senderName.trim();
    }

    public String resolveOrBlank() {
        if (valid(explicitSender)) {
            cache(explicitSender);
            return explicitSender;
        }

        if (valid(notificationSender)) {
            cache(notificationSender);
            return notificationSender;
        }

        String cached = cachedSender;
        Instant cachedTimestamp = cachedAt;
        if (valid(cached)
                && cachedTimestamp != null
                && Instant.now().isBefore(cachedTimestamp.plus(CACHE_TTL))) {
            return cached;
        }

        return discoverFromBrevo();
    }

    public boolean isReady() {
        return !resolveOrBlank().isBlank();
    }

    public boolean hasExplicitConfiguration() {
        return valid(explicitSender) || valid(notificationSender);
    }

    private String discoverFromBrevo() {
        if (apiKey.isBlank() || sendersUrl.isBlank()) {
            return "";
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("api-key", apiKey);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    sendersUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    new ParameterizedTypeReference<>() {});

            if (!response.getStatusCode().is2xxSuccessful()
                    || response.getBody() == null) {
                log.warn(
                        "Brevo sender discovery failed httpStatus={}",
                        response.getStatusCode().value());
                return "";
            }

            Object rawSenders = response.getBody().get("senders");
            if (!(rawSenders instanceof List<?> senders)) {
                log.warn("Brevo sender discovery returned no sender list");
                return "";
            }

            String preferred = findActiveByName(senders);
            if (!preferred.isBlank()) {
                cache(preferred);
                log.info(
                        "Resolved AAL transactional sender from Brevo sender registry sender={}",
                        mask(preferred));
                return preferred;
            }

            String onlyActive = findOnlyActiveSender(senders);
            if (!onlyActive.isBlank()) {
                cache(onlyActive);
                log.info(
                        "Resolved AAL transactional sender from sole active Brevo sender sender={}",
                        mask(onlyActive));
                return onlyActive;
            }

            log.error(
                    "Brevo account has no unambiguous active transactional sender. "
                            + "Configure AAL_BREVO_SENDER_EMAIL or verify one Brevo sender.");
            return "";

        } catch (RestClientException ex) {
            log.error(
                    "Brevo sender discovery request failed type={}",
                    ex.getClass().getSimpleName());
            return "";
        }
    }

    private String findActiveByName(List<?> senders) {
        if (senderName.isBlank()) {
            return "";
        }

        for (Object item : senders) {
            if (!(item instanceof Map<?, ?> sender)) {
                continue;
            }

            if (!Boolean.TRUE.equals(sender.get("active"))) {
                continue;
            }

            String name = stringValue(sender.get("name"));
            String email = stringValue(sender.get("email"));

            if (senderName.equalsIgnoreCase(name) && valid(email)) {
                return email;
            }
        }

        return "";
    }

    private String findOnlyActiveSender(List<?> senders) {
        String active = "";

        for (Object item : senders) {
            if (!(item instanceof Map<?, ?> sender)) {
                continue;
            }

            if (!Boolean.TRUE.equals(sender.get("active"))) {
                continue;
            }

            String email = stringValue(sender.get("email"));
            if (!valid(email)) {
                continue;
            }

            if (!active.isBlank()) {
                return "";
            }

            active = email;
        }

        return active;
    }

    private void cache(String email) {
        cachedSender = email;
        cachedAt = Instant.now();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean valid(String value) {
        return value != null
                && !value.isBlank()
                && EMAIL.matcher(value).matches()
                && !value.contains("localhost");
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
