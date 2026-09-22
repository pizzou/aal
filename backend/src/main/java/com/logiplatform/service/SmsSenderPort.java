package com.logiplatform.service;

public interface SmsSenderPort {
    SendResult send(String recipient, String message);
    record SendResult(boolean sent, String provider, String providerReference, String errorDetail) {}
}
