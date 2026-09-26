# AAL Disaster Recovery Runbook

## Targets
- RPO: 15 minutes for PostgreSQL WAL/PITR when the production backup stack is configured.
- RTO: 60 minutes for a tested restore into the approved production environment.

## Required controls
1. Automated PostgreSQL base backups plus WAL archiving.
2. Off-site object storage for database backups and cargo evidence.
3. Versioned object storage with encryption and retention controls.
4. Flyway migration history backed up with the database.
5. A restore drill at least quarterly.

## Restore procedure
1. Provision a clean PostgreSQL instance.
2. Restore the latest base backup.
3. Replay WAL to the selected recovery point.
4. Verify Flyway history and application schema.
5. Restore encrypted object storage/evidence.
6. Start the backend with readiness checks enabled.
7. Run the database-consistency endpoint and smoke tests.
8. Verify tenant isolation, booking state, invoice/payment balances and integration queues.
9. Record actual RPO/RTO and any failed controls.

## Evidence
A backup is not considered production evidence until a restore has completed successfully and the restored application passes the smoke suite.
