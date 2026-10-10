package com.logiplatform.service;

import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.model.CommercialInvoice;
import com.logiplatform.repository.CommercialInvoiceRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PayPalPaymentGatewayService {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate rest;
    private final BillingService billing;
    private final CommercialInvoiceRepository invoices;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final List<String> allowedRedirectOrigins;

    public PayPalPaymentGatewayService(
            RestTemplate rest,
            BillingService billing,
            CommercialInvoiceRepository invoices,
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            ObjectMapper mapper,
            @Value("${paypal.base-url:${PAYPAL_BASE_URL:https://api-m.sandbox.paypal.com}}") String baseUrl,
            @Value("${paypal.client-id:${PAYPAL_CLIENT_ID:}}") String clientId,
            @Value("${paypal.client-secret:${PAYPAL_CLIENT_SECRET:}}") String clientSecret,
            @Value("${paypal.allowed-return-origins:${security.cors.allowed-origins:http://localhost:3000}}") String allowedReturnOrigins) {
        this.rest = rest;
        this.billing = billing;
        this.invoices = invoices;
        this.db = db;
        this.mapper = mapper;
        this.baseUrl = strip(baseUrl);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.allowedRedirectOrigins = Arrays.stream(allowedReturnOrigins == null ? new String[0] : allowedReturnOrigins.split(","))
                .map(PayPalPaymentGatewayService::normalizeOrigin)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    @Transactional
    public Map<String, Object> createOrder(
            UUID invoiceId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String returnUrl,
            String cancelUrl) {
        requireConfigured();
        if (invoiceId == null || amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid invoiceId and amount are required");
        }
        String currencyCode = normalizeCurrency(currency);
        String requestId = normalizeKey(idempotencyKey);

        // Replaying the same key returns the previously created order, while a
        // key reused for a different invoice or amount is a hard conflict.
        List<Map<String,Object>> priorRequests = db.queryForList("""
                SELECT invoice_id, provider_order_id, status, amount, currency
                  FROM payment_gateway_transactions
                 WHERE tenant_id=? AND provider='PAYPAL' AND idempotency_key=?
                """, TenantContext.getTenantId(), requestId);
        if (!priorRequests.isEmpty()) {
            Map<String,Object> prior = priorRequests.get(0);
            if (!invoiceId.equals(asUuid(prior.get("invoice_id")))
                    || money(prior.get("amount")).compareTo(amount) != 0
                    || !currencyCode.equalsIgnoreCase(String.valueOf(prior.get("currency")))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency-Key was already used for a different PayPal order request");
            }
            String existingOrder = String.valueOf(prior.getOrDefault("provider_order_id", ""));
            if (!existingOrder.isBlank() && !"null".equalsIgnoreCase(existingOrder)) {
                return Map.of("provider", "PAYPAL", "orderId", existingOrder,
                        "status", String.valueOf(prior.getOrDefault("status", "CREATED")),
                        "invoiceId", invoiceId, "amount", amount, "currency", currencyCode);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The original PayPal order request has no provider order id; review the gateway transaction");
        }

        CommercialInvoice invoice = invoiceForUpdate(invoiceId);
        validateOrderAmount(invoice, amount, currencyCode);
        String safeReturnUrl = validateRedirectUrl(returnUrl);
        String safeCancelUrl = validateRedirectUrl(cancelUrl);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", "CAPTURE");
        payload.put("purchase_units", java.util.List.of(Map.of(
                "reference_id", invoiceId.toString(),
                "amount", Map.of("currency_code", currencyCode, "value", providerAmount(amount)))));
        if (safeReturnUrl != null || safeCancelUrl != null) {
            Map<String,Object> applicationContext = new LinkedHashMap<>();
            if (safeReturnUrl != null) applicationContext.put("return_url", safeReturnUrl);
            if (safeCancelUrl != null) applicationContext.put("cancel_url", safeCancelUrl);
            applicationContext.put("user_action", "PAY_NOW");
            payload.put("application_context", applicationContext);
        }

        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(accessToken());
        headers.set("PayPal-Request-Id", requestId);
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                baseUrl + "/v2/checkout/orders",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                MAP_TYPE);

        Map<String, Object> body = body(response);
        String orderId = String.valueOf(body.getOrDefault("id", ""));
        if (orderId.isBlank() || "null".equalsIgnoreCase(orderId)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "PayPal did not return an order id; no payment should be attempted");
        }
        recordGateway(invoiceId, String.valueOf(body.getOrDefault("status", "CREATED")),
                amount, currencyCode, requestId, orderId, null, body);
        return Map.of(
                "provider", "PAYPAL",
                "orderId", orderId,
                "status", String.valueOf(body.getOrDefault("status", "CREATED")),
                "invoiceId", invoiceId,
                "amount", amount,
                "currency", currencyCode,
                "raw", body);
    }

    @Transactional
    public Map<String, Object> captureOrder(
            UUID invoiceId,
            BigDecimal expectedAmount,
            String currency,
            String orderId,
            String idempotencyKey) {
        requireConfigured();
        if (invoiceId == null || orderId == null || orderId.isBlank()
                || expectedAmount == null || expectedAmount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Valid invoiceId, positive expectedAmount and PayPal orderId are required");
        }
        normalizeKey(idempotencyKey); // Validate the caller's request key; capture is keyed canonically by order id.
        String currencyCode = normalizeCurrency(currency);
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new IllegalStateException("Tenant context is required");

        CommercialInvoice invoice = invoiceForUpdate(invoiceId);

        // A caller must not be able to capture an arbitrary PayPal order and
        // apply its proceeds to an unrelated invoice in this tenant.
        List<Map<String,Object>> registeredOrders = db.queryForList("""
                SELECT invoice_id, status, amount, currency, provider_capture_id
                  FROM payment_gateway_transactions
                 WHERE tenant_id=? AND provider='PAYPAL' AND provider_order_id=?
                 ORDER BY updated_at DESC LIMIT 1
                """, tenantId, orderId);
        if (registeredOrders.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PayPal order was not created for this tenant by this application");
        }
        Map<String,Object> registered = registeredOrders.get(0);
        if (!invoiceId.equals(asUuid(registered.get("invoice_id")))
                || money(registered.get("amount")).compareTo(expectedAmount) != 0
                || !currencyCode.equalsIgnoreCase(String.valueOf(registered.get("currency")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PayPal order does not match the requested invoice, amount and currency");
        }

        String existingStatus = String.valueOf(registered.getOrDefault("status", ""));
        String existingCaptureId = String.valueOf(registered.getOrDefault("provider_capture_id", ""));
        if ("COMPLETED".equalsIgnoreCase(existingStatus)
                && !existingCaptureId.isBlank() && !"null".equalsIgnoreCase(existingCaptureId)) {
            return Map.of("provider", "PAYPAL", "orderId", orderId, "status", "COMPLETED",
                    "captureId", existingCaptureId, "invoiceId", invoiceId,
                    "amount", expectedAmount, "currency", currencyCode, "idempotentReplay", true);
        }

        // Check current invoice balance only for a new capture. A replay after
        // the successful payment may find a zero balance and must still return
        // the stored capture result rather than attempting another payment.
        validateOrderAmount(invoice, expectedAmount, currencyCode);

        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(accessToken());
        String captureRequestId = captureProviderRequestId(tenantId, orderId);
        headers.set("PayPal-Request-Id", captureRequestId);
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                baseUrl + "/v2/checkout/orders/" + encode(orderId) + "/capture",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                MAP_TYPE);
        Map<String, Object> body = body(response);

        String status = String.valueOf(body.getOrDefault("status", ""));
        if (!"COMPLETED".equalsIgnoreCase(status)) {
            recordGateway(invoiceId, status.isBlank() ? "UNKNOWN" : status,
                    expectedAmount, currencyCode, captureDatabaseKey(orderId), orderId, null, body);
            return Map.of("provider", "PAYPAL", "orderId", orderId,
                    "status", status.isBlank() ? "UNKNOWN" : status, "invoiceId", invoiceId);
        }

        verifyInvoiceReference(body, invoiceId);
        Map<String, Object> capture = firstCapture(body);
        if (!"COMPLETED".equalsIgnoreCase(String.valueOf(capture.getOrDefault("status", "")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PayPal order completed without a completed capture record");
        }
        Map<String,Object> captureAmount = objectMap(capture.get("amount"));
        BigDecimal paid = money(captureAmount.get("value"));
        String paidCurrency = String.valueOf(captureAmount.getOrDefault("currency_code", ""));
        if (paid.signum() <= 0 || paid.compareTo(expectedAmount) != 0
                || !currencyCode.equalsIgnoreCase(paidCurrency)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PayPal capture amount or currency does not match the invoice payment");
        }

        String captureId = String.valueOf(capture.getOrDefault("id", ""));
        if (captureId.isBlank() || "null".equalsIgnoreCase(captureId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PayPal returned a completed capture without a provider capture id");
        }

        // The capture identity—not a caller-generated retry key—is the canonical
        // payment idempotency key. The same capture therefore cannot post twice.
        billing.recordPayment(invoiceId, paid, paidCurrency,
                "PAYPAL_CAPTURE:" + captureId, "PAYPAL:" + orderId);
        recordGateway(invoiceId, status, paid, paidCurrency, captureDatabaseKey(orderId), orderId, captureId, body);
        return Map.of("provider", "PAYPAL", "orderId", orderId, "status", status,
                "captureId", captureId, "amount", paid, "currency", paidCurrency,
                "invoiceId", invoiceId);
    }

    private CommercialInvoice invoiceForUpdate(UUID invoiceId) {
        return invoices.findByTenantIdAndIdForUpdate(TenantContext.getTenantId(), invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
    }

    private static void validateOrderAmount(CommercialInvoice invoice, BigDecimal amount, String currency) {
        if (invoice == null || amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment amount must be positive");
        }
        if (!invoice.getCurrency().equalsIgnoreCase(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment currency does not match invoice currency");
        }
        if (amount.compareTo(invoice.getBalance()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment exceeds invoice balance");
        }
    }

    private void recordGateway(UUID invoiceId, String status, BigDecimal amount, String currency,
                               String idempotencyKey, String orderId, String captureId, Map<String,Object> body) {
        UUID tenant = TenantContext.getTenantId();
        String json;
        try { json = mapper.writeValueAsString(body); }
        catch (Exception ex) { json = "{}"; }
        db.update("""
            INSERT INTO payment_gateway_transactions
              (id,tenant_id,invoice_id,provider,provider_order_id,provider_capture_id,status,amount,currency,idempotency_key,response_json)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (tenant_id,provider,idempotency_key) DO UPDATE SET
              provider_order_id=EXCLUDED.provider_order_id,
              provider_capture_id=EXCLUDED.provider_capture_id,
              status=EXCLUDED.status,
              amount=EXCLUDED.amount,
              currency=EXCLUDED.currency,
              response_json=EXCLUDED.response_json,
              updated_at=now()
            """, UUID.randomUUID(), tenant, invoiceId, "PAYPAL", orderId, captureId, status, amount, currency, idempotencyKey, json);
    }

    private String accessToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(clientId, clientSecret);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        org.springframework.util.LinkedMultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                baseUrl + "/v1/oauth2/token", HttpMethod.POST,
                new HttpEntity<>(form, headers), MAP_TYPE);
        String token = String.valueOf(body(response).getOrDefault("access_token", ""));
        if (token.isBlank()) throw new IllegalStateException("PayPal access token was not returned");
        return token;
    }

    private void requireConfigured() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PayPal credentials are not configured");
        }
    }

    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        return response.getBody() == null ? Map.of() : response.getBody();
    }

    private static Map<String, Object> firstCapture(Map<String, Object> body) {
        Object units = body.get("purchase_units");
        if (!(units instanceof List<?> list) || list.size() != 1 || !(list.get(0) instanceof Map<?,?> unit)) {
            return Map.of();
        }
        Object payments = unit.get("payments");
        if (!(payments instanceof Map<?, ?> p)) return Map.of();
        Object captures = p.get("captures");
        if (!(captures instanceof List<?> capturesList) || capturesList.size() != 1
                || !(capturesList.get(0) instanceof Map<?,?> capture)) return Map.of();
        return objectMap(capture);
    }

    private static BigDecimal money(Object value) {
        if (value instanceof Map<?, ?> raw) return money(raw.get("value"));
        if (value == null) return BigDecimal.ZERO;
        try { return new BigDecimal(value.toString()); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private static UUID asUuid(Object value) {
        if (value instanceof UUID id) return id;
        if (value == null) return null;
        try { return UUID.fromString(value.toString()); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private static Map<String,Object> objectMap(Object value) {
        if (!(value instanceof Map<?,?> raw)) return Map.of();
        Map<String,Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static void verifyInvoiceReference(Map<String,Object> response, UUID invoiceId) {
        Object rawUnits = response.get("purchase_units");
        if (!(rawUnits instanceof List<?> units) || units.size() != 1 || !(units.get(0) instanceof Map<?,?> unit)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PayPal capture response does not contain exactly one expected purchase unit");
        }
        String referenceId = String.valueOf(unit.get("reference_id") == null ? "" : unit.get("reference_id"));
        if (!invoiceId.toString().equalsIgnoreCase(referenceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PayPal purchase unit is not bound to the requested invoice");
        }
    }

    private String validateRedirectUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!"https".equals(scheme) && !"http".equals(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Invalid redirect URI");
            }
            String origin = normalizeOrigin(scheme + "://" + uri.getRawAuthority());
            if (!allowedRedirectOrigins.contains(origin)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "PayPal return/cancel URL origin is not in paypal.allowed-return-origins");
            }
            return uri.toString();
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid PayPal return/cancel URL", ex);
        }
    }

    private static String normalizeOrigin(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.isBlank() || (!"http".equals(scheme) && !"https".equals(scheme)) || uri.getUserInfo() != null) return "";
            int port = uri.getPort();
            if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) port = -1;
            return scheme + "://" + host + (port < 0 ? "" : ":" + port);
        } catch (Exception ex) {
            return "";
        }
    }

    private static Map<String, Object> toMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((k, v) -> map.put(String.valueOf(k), v));
        return map;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private static String normalizeCurrency(String value) {
        String v = value == null || value.isBlank() ? "USD" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!v.matches("[A-Z]{3}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Currency must be a 3-letter code");
        return v;
    }

    private static String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is required for PayPal order creation and capture");
        }
        String key = value.trim();
        // PayPal-Request-Id is limited by the provider contract; reject overly long
        // keys before calling PayPal so provider errors cannot leave ambiguous orders.
        if (key.length() > 38) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PayPal idempotency key must not exceed 38 characters");
        }
        return key;
    }

    private static String providerAmount(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PayPal payments support at most two decimal places for this payment amount", ex);
        }
    }

    private static String captureDatabaseKey(String orderId) {
        return "PAYPAL_CAPTURE_ORDER:" + orderId;
    }

    private static String captureProviderRequestId(UUID tenantId, String orderId) {
        return UUID.nameUUIDFromBytes((tenantId + ":PAYPAL_CAPTURE:" + orderId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String strip(String value) { return value == null ? "" : value.trim().replaceAll("/+$", ""); }
    private static String encode(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
}
