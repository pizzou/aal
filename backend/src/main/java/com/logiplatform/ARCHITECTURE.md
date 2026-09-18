# Backend package architecture

The backend uses a strict layer-oriented package structure. Business features are no longer mixed under `domain/*`.

```text
com.logiplatform
├── config/        # Spring, JPA, REST, reporting and production configuration
├── security/      # JWT, CSRF, authentication filters and principals
├── tenancy/       # Tenant context and tenant-aware datasource
├── common/        # Cross-cutting exception/correlation handling
├── controller/    # HTTP/REST controllers only
├── dto/           # Request/response DTOs and validation contracts
├── service/       # Business/application services and integration ports/adapters
├── repository/    # Spring Data/JDBC persistence interfaces
└── model/         # JPA entities, enums and persistence/domain models
```

## Rules

1. Controllers expose HTTP endpoints and depend on DTOs/services/repositories as required.
2. DTOs contain API contracts and validation; they do not contain persistence logic.
3. Services contain business workflows and integrations.
4. Repositories contain persistence access only.
5. Models contain entities/enums/value-like persistence structures only.
6. Security, tenancy, configuration and common cross-cutting concerns stay outside the business layers.
7. AAL-specific behavior is implemented through tenant configuration/master data rather than hard-coded into the core package structure.
8. New features must place each class in the correct layer instead of creating a mixed feature package.

## Enterprise/AAL Phase 1 placement

- `EnterpriseLogisticsController` -> `controller`
- `EnterpriseLogisticsDtos` -> `dto`
- `OceanVoyage`, `OceanContainer`, `OceanBooking`, `RoadConsignment`, `RailConsignment` -> `model`
- `OceanVoyageRepository`, `OceanContainerRepository`, `OceanBookingRepository`, `RoadConsignmentRepository`, `RailConsignmentRepository` -> `repository`
- `V22__enterprise_logistics_core.sql` remains the persistence migration.

This structure is intentionally simple and predictable for a growing production logistics platform: developers can locate a controller, DTO, service, repository or model without searching through feature folders.
