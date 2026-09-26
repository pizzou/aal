# AAL Integration Provider Onboarding

AAL does not invent airline endpoints. A provider-specific adapter is added only after the provider supplies its real API specification, credentials, authentication method, error model, idempotency semantics, webhook contract and sandbox/production details.

## Required provider package
- Base URL(s) and API version.
- Authentication: OAuth2, API key, mTLS or documented signing scheme.
- Credential rotation and expiry rules.
- Capability matrix: schedule, capacity, booking, amendment, cancellation, status, AWB, webhook.
- Request/response schemas and validation rules.
- Provider error codes and retry semantics.
- Idempotency rules for every mutating operation.
- Webhook signature, timestamp, nonce and event-ID rules.
- Reconciliation/search operation for uncertain outcomes.
- Rate limits and service windows.

## Adapter boundary
Implement `AirCargoProviderPort` only. Do not place provider-specific XML/JSON mapping in the generic adapter. The generic HTTP adapter remains the fallback abstraction.

## Production gate
A provider is not marked ACTIVE until credentials are registered, capability discovery succeeds, health checks pass, webhook verification is configured and reconciliation has been tested against provider sandbox behavior.
