package com.logiplatform.service;

import java.util.Optional;

/**
 * DIFFERENT SITUATION FROM CarrierGatewayPort (airline booking/capacity), and worth
 * being precise about why: airline cargo BOOKING requires an actual carrier/IATA
 * business relationship — no signup form gets you that. Flight STATUS/tracking data
 * is different — providers like AviationStack, AirLabs, and FlightAPI offer
 * self-service signup (email address, no business relationship) with free tiers.
 * AviationStackFlightStatusAdapter is a genuine, complete implementation against
 * AviationStack's real, publicly documented API contract (verified against their
 * actual documented sample response before being written — see
 * RLS_VERIFICATION.md), not a permanent mock. It requires a free API key to
 * function, same as SmtpNotificationAdapter requires real SMTP credentials.
 *
 * NoOpFlightStatusAdapter is the default (active without a configured key) — always
 * returns empty, never breaks a shipment operation that checks flight status.
 *
 * SCOPE NOTE: this is flight STATUS/delay lookup for a shipment's own flight number,
 * not "route optimization" — evaluating alternate routings/connections across many
 * possible flights is a much larger problem (schedule search + connection feasibility
 * + cargo-specific constraints) that a status lookup alone doesn't solve. This closes
 * part of the "predictive milestone tracking" gap, not the full "route optimization"
 * bullet from the original feature list.
 */
public interface FlightStatusPort {
    Optional<FlightStatusResult> getStatus(String flightIataCode, String flightDate);

    record FlightStatusResult(
            String flightStatus,
            int departureDelayMinutes,
            int arrivalDelayMinutes,
            boolean significantDelay
    ) {}
}
