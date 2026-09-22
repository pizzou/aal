package com.logiplatform.controller;

import com.logiplatform.service.PayPalPaymentGatewayService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments/gateway")
public class PaymentGatewayController {
    private final PayPalPaymentGatewayService paypal;

    public PaymentGatewayController(PayPalPaymentGatewayService paypal) {
        this.paypal = paypal;
    }

    @PostMapping("/paypal/orders")
    public ResponseEntity<Map<String,Object>> createPaypalOrder(@Valid @RequestBody CreateOrderRequest r) {
        return ResponseEntity.ok(paypal.createOrder(
                r.invoiceId(), r.amount(), r.currency(), r.idempotencyKey(), r.returnUrl(), r.cancelUrl()));
    }

    @PostMapping("/paypal/orders/{orderId}/capture")
    public ResponseEntity<Map<String,Object>> capturePaypalOrder(
            @PathVariable String orderId,
            @Valid @RequestBody CaptureOrderRequest r) {
        return ResponseEntity.ok(paypal.captureOrder(
                r.invoiceId(), r.expectedAmount(), r.currency(), orderId, r.idempotencyKey()));
    }

    public record CreateOrderRequest(
            @NotNull UUID invoiceId,
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
            String idempotencyKey,
            String returnUrl,
            String cancelUrl) {}

    public record CaptureOrderRequest(
            @NotNull UUID invoiceId,
            @NotNull @Positive BigDecimal expectedAmount,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
            String idempotencyKey) {}
}
