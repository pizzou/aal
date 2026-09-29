# AAL Real Airline Integration

AAL's air-cargo integration is provider-neutral at the application layer. Airline-specific connectivity lives behind `AirCargoProviderPort`, while search, booking, shipment and ETA workflows keep the same domain contract.

## Currently implemented

### CargoAi CargoCONNECT (`CARGOAI`)

The adapter supports the live CargoCONNECT workflow used by AAL:

- live schedule/rate search
- live airline availability filtering
- selected-offer booking using the provider's flight/rate identifiers
- booking status handling
- cancellation
- Track & Trace by provider reference
- provider webhooks and reconciliation hooks already present in AAL
- normalized rate, currency, price and bookability fields

CargoAi credentials are backend-only and are read from Render/server environment variables. No secret is committed to source control.

### Qatar Airways Cargo direct (`QATAR`)

A direct Qatar Availability & Rate adapter is implemented. It sends the documented availability request model, including agent identity, origin/destination, product/commodity and shipment quantity, and normalizes the returned flight itineraries and rate details into AAL offers.

The direct Qatar adapter is intentionally **search-only** until AAL receives the current tenant-specific Booking API contract and production endpoint from Qatar Cargo. The application will not invent a private booking payload or silently create an internal booking from a Qatar live offer.

Required environment variables:

```text
AIRCARGO_QATAR_ENABLED=true
AIRCARGO_QATAR_BASE_URL=<Qatar-provided API base URL>
AIRCARGO_QATAR_API_KEY=<Qatar-provided credential>
AIRCARGO_QATAR_AGENT_IATA=<AAL agent IATA>
AIRCARGO_QATAR_AGENT_CASS=<AAL agent CASS>
AIRCARGO_QATAR_COMMODITY_CODE=<Qatar Cargo commodity code>
```

Optional routing/product settings are available in `application.properties`.

## Airline coverage model

| Carrier / network | AAL connection path | Booking in AAL | Tracking in AAL | Credential/spec needed |
|---|---|---:|---:|---|
| Qatar Airways Cargo | Direct Availability adapter + CargoAi network | Direct adapter pending current Qatar Booking API contract; CargoAi offers may be bookable when returned as bookable | CargoAi path when the offer/booking is returned there | Qatar developer/partner credentials for direct path |
| Ethiopian Cargo | CargoAi/WebCargo partner networks | Through CargoAi when a returned offer is bookable | Through CargoAi tracking when available | CargoAi or WebCargo commercial/API access |
| Brussels Airlines Cargo | Lufthansa Cargo network / CargoAi network | Through a supported live network when a bookable offer is returned | CargoAi when available; direct Lufthansa Cargo AWB tracking adapter is also available | Lufthansa partner credentials are required for direct smartBooking |
| RwandAir Cargo | Direct adapter not enabled because a public cargo developer API was not verified | Requires RwandAir Cargo API/EDI contract | Requires RwandAir Cargo API/EDI contract or supported aggregator coverage | RwandAir Cargo API/EDI specification + credentials |
| Kenya Airways Cargo | Direct adapter not enabled because a public cargo developer API was not verified | Requires KQ Cargo API/EDI contract or supported aggregator | Requires KQ Cargo API/EDI contract or supported aggregator | KQ Cargo connectivity specification + credentials |

## Provider routing rules

`AIRCARGO_PROVIDER_CODE=AUTO` is the recommended production setting.

- Search can fan out to every configured live provider.
- Each stored live offer has its own `providerCode` and `providerReference`.
- A booking is sent back to the provider that supplied the selected offer.
- Amendments and cancellations are sent back to the provider recorded on the booking.
- Live tracking prefers the booking provider and provider reference.
- A provider failure does not erase successful offers returned by other providers.

This prevents a Qatar offer from being booked against CargoAi credentials, or a CargoAi booking from later being polled through an unrelated active provider.


## Connectivity verification

The authenticated operator endpoint `POST /api/air-cargo/integration/verify-all` performs an intentional live search against every configured live search provider without persisting the returned offers. It reports each provider's status, latency, offer count, bookable-offer count and carrier codes. `GET /api/air-cargo/integration/health` reports configuration/capabilities but is not itself proof that an external endpoint is reachable.

The Air Cargo page exposes both checks as **Test active provider** and **Test connected airlines**. Use a real route and shipment weight; a `CONNECTED` result means the provider call completed successfully, while `CONNECTED_NO_MATCHES` means the provider answered but returned no offer for that request.

## Resilience and performance changes

- The control tower loads core dashboard and recent shipments independently, so one slow/failing endpoint no longer blanks the other panel.
- Auxiliary health, fleet and operational widgets load independently and refresh in the background.
- Protected GET requests use in-flight de-duplication, a short client cache, retry handling for transient gateway failures, and a bounded stale-data grace period for transient network/timeout failures after a successful response.
- Authentication restores a previously validated browser session immediately and validates the server session in the background, avoiding a full-page wait during a Render cold start. A real 401/403 still clears the local session.
- Air-cargo provider searches fan out concurrently and preserve successful provider results when another configured provider fails.
- Air-cargo booking lists are capped to the latest 50 records and shipment lookups on detail pages run concurrently.

These measures improve continuity, but an external provider with a slow or unavailable API can still increase the latency of a live airline search. Provider latency is surfaced by the verification endpoint instead of being hidden.

## Production credentials

Do not place airline secrets in frontend code, Git, SQL migrations or browser-visible environment variables. Store them in the backend deployment environment (for example Render Environment Variables) and rotate them through the airline/provider's supported credential process.

Before enabling a provider in production, complete:

1. UAT/sandbox authentication.
2. Live search/rate validation on representative lanes.
3. Booking idempotency and uncertain-outcome reconciliation tests.
4. Cancellation/amendment tests where the provider supports them.
5. Live tracking/AWB event validation.
6. Webhook signature verification and replay protection.
7. Rate-limit and circuit-breaker tests.
8. Production credential cutover without source-code changes.

### Lufthansa Cargo direct tracking (`LHCARGO`)

A direct Lufthansa Cargo shipment-tracking adapter is included. It accepts an AWB provider reference such as `AWB:020-12345678` and calls the Lufthansa Cargo shipment-tracking API. It is tracking-only because Lufthansa's smartBooking API is a private registered partner API; the public service agreement states that the API key and endpoint are issued after registration.

This gives AAL a concrete direct tracking path for the Lufthansa Cargo network, while live quote/booking remains behind the official partner smartBooking contract rather than an invented payload.
