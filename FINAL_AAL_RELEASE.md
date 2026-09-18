# Aviation Africa Logistics — Final High-End Logistics Platform

## Application
- Native AAL logistics operating platform; Excel is migration-only.
- Internal AAL Control Tower is the primary authenticated workspace.
- Customer portal and public tracking are separate presentation layers.
- Air, ocean, road, rail and multimodal operations are represented in the platform.
- Commercial, finance, warehouse, fleet/GPS, documents/customs, exceptions, audit and notifications are connected to the shipment lifecycle.

## Release additions
- Quote → shipment conversion API.
- Quote → invoice workflow.
- First-class transport-leg milestones/documents/costs and equipment references.
- Finance FX rates, financial periods and credit/debit adjustments.
- TOTP MFA provisioning/verification.
- Secure public tracking expiry/revocation.
- Geofence registry/evaluation.
- Notification retry queue.
- Integration registry and attempt history.
- Kafka-backed GPS event publication with PostgreSQL durability retained.
- Production Compose with PostgreSQL, Redis, Kafka, backend, frontend, Nginx and Prometheus/Grafana.
- Kubernetes baseline, GitHub CI/security scan, backup and restore-drill scripts.

## Production activation
Provider-specific integrations remain disabled until AAL has real provider credentials, contracts and sandbox/UAT acknowledgement. Configure the required environment variables in the deployment secret store.

Backend: `cd backend && mvn clean verify`
Frontend: `cd frontend && npm ci && npm run build`
Production: `docker compose --env-file .env.production -f docker-compose.production.yml up -d --build`
