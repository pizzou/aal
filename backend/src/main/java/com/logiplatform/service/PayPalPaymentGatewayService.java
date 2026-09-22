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
import java.util.LinkedHashMap;
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

    public PayPalPaymentGatewayService(
            RestTemplate rest,
            BillingService billing,
            CommercialInvoiceRepository invoices,
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            ObjectMapper mapper,
            @Value("${paypal.base-url:${PAYPAL_BASE_URL:https://api-m.sandbox.paypal.com}}") String baseUrl,
            @Value("${paypal.client-id:${PAYPAL_CLIENT_ID:}}") String clientId,
            @Value("${paypal.client-secret:${PAYPAL_CLIENT_SECRET:}}") String clientSecret) {
        this.rest = rest;
        this.billing = billing;
        this.invoices = invoices;
        this.db = db;
        this.mapper = mapper;
        this.baseUrl = strip(baseUrl);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
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
        CommercialInvoice invoice = invoiceForUpdate(invoiceId);
        validateOrderAmount(invoice, amount, currencyCode);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", "CAPTURE");
        payload.put("purchase_units", java.util.List.of(Map.of(
                "reference_id", invoiceId.toString(),
                "amount", Map.of("currency_code", currencyCode, "value", amount.setScale(2).toPlainString()))));
        if ((returnUrl != null && !returnUrl.isBlank()) || (cancelUrl != null && !cancelUrl.isBlank())) {
            payload.put("application_context", Map.of(
                    "return_url", returnUrl == null ? "" : returnUrl,
                    "cancel_url", cancelUrl == null ? "" : cancelUrl,
                    "user_action", "PAY_NOW"));
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
        recordGateway(invoiceId, "CREATED", amount, currencyCode, requestId, orderId, null, body);
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
        if (invoiceId == null || orderId == null || orderId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invoiceId and PayPal orderId are required");
        }
        String requestId = normalizeKey(idempotencyKey);
        String currencyCode = normalizeCurrency(currency);
        CommercialInvoice invoice = invoiceForUpdate(invoiceId);
        validateOrderAmount(invoice, expectedAmount, currencyCode);
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(accessToken());
        headers.set("PayPal-Request-Id", requestId);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                baseUrl + "/v2/checkout/orders/" + encode(orderId) + "/capture",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                MAP_TYPE);
        Map<String, Object> body = body(response);

        String status = String.valueOf(body.getOrDefault("status", ""));
        if (!"COMPLETED".equalsIgnoreCase(status)) {
            recordGateway(invoiceId, status, expectedAmount, currencyCode, requestId, orderId, null, body);
            return Map.of("provider", "PAYPAL", "orderId", orderId, "status", status, "raw", body);
        }

        Map<String, Object> capture = firstCapture(body);
        BigDecimal paid = money(capture.get("amount"));
        String paidCurrency = String.valueOf(capture.getOrDefault("currency_code", normalizeCurrency(currency)));
        if (paid.signum() <= 0 || expectedAmount == null || paid.compareTo(expectedAmount) != 0
                || !paidCurrency.equalsIgnoreCase(normalizeCurrency(currency))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PayPal capture does not match the expected invoice payment");
        }

        String captureId = String.valueOf(capture.getOrDefault("id", ""));
        billing.recordPayment(invoiceId, paid, paidCurrency, requestId, "PAYPAL:" + orderId);
        recordGateway(invoiceId, status, paid, paidCurrency, requestId, orderId, captureId, body);
        return Map.of("provider", "PAYPAL", "orderId", orderId, "status", status,
                "amount", paid, "currency", paidCurrency, "invoiceId", invoiceId, "raw", body);
    }

    private CommercialInvoice invoiceForUpdate(UUID invoiceId) {
        return invoices.findByTenantIdAndIdForUpdate(TenantContext.getTenantId(), invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
    }

    private static void validateOrderAmount(CommercialInvoice invoice, BigDecimal amount, String currency) {
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstCapture(Map<String, Object> body) {
        Object units = body.get("purchase_units");
        if (!(units instanceof java.util.List<?> list) || list.isEmpty()) return Map.of();
        Object payments = ((Map<?, ?>) list.get(0)).get("payments");
        if (!(payments instanceof Map<?, ?> p)) return Map.of();
        Object captures = p.get("captures");
        if (!(captures instanceof java.util.List<?> capturesList) || capturesList.isEmpty()) return Map.of();
        Object capture = capturesList.get(0);
        return capture instanceof Map<?, ?> raw ? toMap(raw) : Map.of();
    }

    private static BigDecimal money(Object value) {
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (value instanceof Map<?, ?> raw) return money(raw.get("value"));
        if (value == null) return BigDecimal.ZERO;
        try { return new BigDecimal(value.toString()); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
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
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
    }

    private static String strip(String value) { return value == null ? "" : value.trim().replaceAll("/+$", ""); }
    private static String encode(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
}
