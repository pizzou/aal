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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

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
    private final String notificationsSender;
    private final String apiKey;
    private final String sendersUrl;
    private final String preferredSenderName;

    private volatile String cachedSender;
    private volatile Instant cachedAt;

    public BrevoSenderResolver(
            @Value("${app.mail.from:}") String explicitSender,
            @Value("${notifications.from-address:}") String notificationsSender,
            @Value("${app.mail.brevo-api-key:}") String apiKey,
            @Value("${app.mail.brevo-senders-url:https://api.brevo.com/v3/senders}") String sendersUrl,
            @Value("${app.mail.sender-name:Aviation Africa Logistics Ltd}") String preferredSenderName) {

        this.restTemplate = new RestTemplate();

        this.explicitSender = normalize(explicitSender);
        this.notificationsSender = normalize(notificationsSender);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.sendersUrl = sendersUrl == null ? "" : sendersUrl.trim();
        this.preferredSenderName =
                preferredSenderName == null
                        ? ""
                        : preferredSenderName.trim();
    }

    public String resolveOrBlank() {
        String explicit = valid(explicitSender)
                ? explicitSender
                : null;

        if (explicit != null) {
            cache(explicit);
            return explicit;
        }

        String notification = valid(notificationsSender)
                ? notificationsSender
                : null;

        if (notification != null) {
            cache(notification);
            return notification;
        }

        String cached = cachedSender;
        Instant timestamp = cachedAt;

        if (cached != null
                && timestamp != null
                && Instant.now().isBefore(timestamp.plus(CACHE_TTL))) {
            return cached;
        }

        return discover();
    }

    public boolean isReady() {
        return !resolveOrBlank().isBlank();
    }

    private String discover() {

        if (apiKey.isBlank() || sendersUrl.isBlank()) {
            return "";
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("api-key", apiKey);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<Map> response =
                    restTemplate.exchange(
                            sendersUrl,
                            HttpMethod.GET,
                            new HttpEntity<>(headers),
                            Map.class);

            if (!response.getStatusCode().is2xxSuccessful()
                    || response.getBody() == null) {
                log.warn(
                        "Unable to resolve Brevo sender: httpStatus={}",
                        response.getStatusCode().value());
                return "";
            }

            Object raw =
                    response.getBody().get("senders");

            if (!(raw instanceof List<?> senders)) {
                log.warn("Brevo sender response did not contain a senders list");
                return "";
            }

            String preferred = findPreferred(senders);

            if (!preferred.isBlank()) {
                cache(preferred);
                return preferred;
            }

            String singleActive = findSingleActive(senders);

            if (!singleActive.isBlank()) {
                cache(singleActive);

                log.info(
                        "Resolved AAL transactional sender from Brevo sender registry sender={}",
                        mask(singleActive));

                return singleActive;
            }

            log.error(
                    "Brevo API returned no unambiguous active sender. "
                            + "Configure AAL_BREVO_SENDER_EMAIL explicitly.");

            return "";

        } catch (RestClientException ex) {
            log.error(
                    "Unable to query Brevo sender registry type={}",
                    ex.getClass().getSimpleName());

            return "";
        }
    }

    private String findPreferred(List<?> senders) {

        for (Object item : senders) {

            if (!(item instanceof Map<?, ?> sender)) {
                continue;
            }

            Object active = sender.get("active");
            Object email = sender.get("email");
            Object name = sender.get("name");

            if (!Boolean.TRUE.equals(active)) {
                continue;
            }

            String senderEmail =
                    email == null ? "" : email.toString().trim();

            String senderName =
                    name == null ? "" : name.toString().trim();

            if (!valid(senderEmail)) {
                continue;
            }

            if (!preferredSenderName.isBlank()
                    && preferredSenderName.equalsIgnoreCase(senderName)) {

                return senderEmail;
            }
        }

        return "";
    }

    private String findSingleActive(List<?> senders) {

        String found = "";

        for (Object item : senders) {

            if (!(item instanceof Map<?, ?> sender)) {
                continue;
            }

            if (!Boolean.TRUE.equals(sender.get("active"))) {
                continue;
            }

            Object email = sender.get("email");

            String senderEmail =
                    email == null ? "" : email.toString().trim();

            if (!valid(senderEmail)) {
                continue;
            }

            if (!found.isBlank()) {
                // More than one active sender and no deterministic match.
                return "";
            }

            found = senderEmail;
        }

        return found;
    }

    private void cache(String email) {
        cachedSender = email;
        cachedAt = Instant.now();
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

        return email.charAt(0)
                + "***"
                + email.substring(at);
    }
}