# AAL API v1

Enterprise control-plane endpoints are versioned under `/api/v1`.

- `GET /api/v1/integrations/health`
- `GET /api/v1/integrations/providers`
- `GET /api/v1/integrations/credentials`
- `POST /api/v1/integrations/accounts`
- `POST /api/v1/integrations/accounts/{accountId}/credentials`
- `DELETE /api/v1/integrations/accounts/{accountId}/credentials/{type}`
- `GET /api/v1/integrations/reconciliation`
- `GET /api/v1/integrations/onerecord/events`
- `GET /api/v1/integrations/cargo-xml/registry`
- `GET /api/v1/control-tower/operational`
- `GET /api/v1/data-quality/database-consistency`

All errors use the common structure:

```json
{
  "timestamp": "...",
  "status": 409,
  "code": "BOOKING_ALREADY_CONFIRMED",
  "message": "...",
  "correlationId": "...",
  "details": []
}
```
