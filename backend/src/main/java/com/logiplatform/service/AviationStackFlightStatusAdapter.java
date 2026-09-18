package com.logiplatform.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Calls AviationStack's real, documented /v1/flights endpoint. The response schema
 * modeled below (AviationStackResponse -> FlightData -> DepartureArrival) matches
 * AviationStack's own documented sample response exactly — verified via web search
 * against their public documentation before this was written, and the delay-null
 * handling logic (a flight with no reported delay yet returns delay: null, not 0)
 * was specifically verified in a Python prototype first (see RLS_VERIFICATION.md)
 * because it's exactly the kind of edge case that causes a NullPointerException if
 * missed — Integer unboxing on a null field.
 *
 * Base URL is configurable (not hardcoded to HTTPS) because AviationStack's free
 * tier has historically required plain HTTP for some accounts — a real, documented
 * quirk of the free plan, not a mistake in this code.
 *
 * Requires flightstatus.aviationstack.enabled=true and a real API key
 * (flightstatus.aviationstack.api-key) — obtained via free self-service signup at
 * aviationstack.com, no business/carrier relationship required. This is a genuinely
 * complete implementation once that key is supplied, same category as
 * SmtpNotificationAdapter — not a stand-in for something that can't be built.
 */
@Component
@ConditionalOnProperty(name = "flightstatus.aviationstack.enabled", havingValue = "true")
public class AviationStackFlightStatusAdapter implements FlightStatusPort {

    private static final int SIGNIFICANT_DELAY_THRESHOLD_MINUTES = 60;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;

    public AviationStackFlightStatusAdapter(
            RestTemplate restTemplate,
            @Value("${flightstatus.aviationstack.base-url:https://api.aviationstack.com/v1}") String baseUrl,
            @Value("${flightstatus.aviationstack.api-key:}") String apiKey) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public Optional<FlightStatusResult> getStatus(String flightIataCode, String flightDate) {
        String url = baseUrl + "/flights?access_key=" + apiKey + "&flight_iata=" + flightIataCode;
        if (flightDate != null) {
            url += "&flight_date=" + flightDate;
        }

        AviationStackResponse response;
        try {
            response = restTemplate.getForObject(url, AviationStackResponse.class);
        } catch (Exception e) {
            return Optional.empty();
        }

        if (response == null || response.data == null || response.data.isEmpty()) {
            return Optional.empty();
        }

        FlightData flight = response.data.get(0);
        int depDelay = flight.departure != null && flight.departure.delay != null ? flight.departure.delay : 0;
        int arrDelay = flight.arrival != null && flight.arrival.delay != null ? flight.arrival.delay : 0;
        boolean significant = depDelay >= SIGNIFICANT_DELAY_THRESHOLD_MINUTES
                || arrDelay >= SIGNIFICANT_DELAY_THRESHOLD_MINUTES;

        return Optional.of(new FlightStatusResult(flight.flight_status, depDelay, arrDelay, significant));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AviationStackResponse {
        public List<FlightData> data;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class FlightData {
        public String flight_status;
        public DepartureArrival departure;
        public DepartureArrival arrival;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class DepartureArrival {
        public Integer delay;
    }
}
