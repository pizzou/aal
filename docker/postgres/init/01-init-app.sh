#!/usr/bin/env bash
set -euo pipefail

# PostgreSQL runs this file only when initializing a new database volume.
# For an existing managed database, run scripts/create_app_role.sql and
# scripts/create_backup_role.sql explicitly during the production preflight.

: "${POSTGRES_USER:?Postgres superuser name is required}"
: "${POSTGRES_DB:?Postgres database name is required}"
: "${SPRING_DATASOURCE_USERNAME:=aal_app}"
: "${SPRING_DATASOURCE_PASSWORD:?SPRING_DATASOURCE_PASSWORD is required to create the application role}"
: "${BACKUP_DB_USERNAME:=aal_backup}"
: "${BACKUP_DB_PASSWORD:?BACKUP_DB_PASSWORD is required to create the backup role}"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=app_role="$SPRING_DATASOURCE_USERNAME" \
  --set=app_password="$SPRING_DATASOURCE_PASSWORD" \
  --set=backup_role="$BACKUP_DB_USERNAME" \
  --set=backup_password="$BACKUP_DB_PASSWORD" \
  --set=database_name="$POSTGRES_DB" <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_role') THEN
    EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_role', :'app_password');
  ELSE
    EXECUTE format('ALTER ROLE %I LOGIN PASSWORD %L', :'app_role', :'app_password');
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'backup_role') THEN
    EXECUTE format('CREATE ROLE %I LOGIN REPLICATION BYPASSRLS PASSWORD %L', :'backup_role', :'backup_password');
  ELSE
    EXECUTE format('ALTER ROLE %I LOGIN REPLICATION BYPASSRLS PASSWORD %L', :'backup_role', :'backup_password');
  END IF;
END
$$;

GRANT CONNECT ON DATABASE :"database_name" TO :"app_role";
GRANT USAGE ON SCHEMA public TO :"app_role";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"app_role";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"app_role";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_role";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO :"app_role";

GRANT CONNECT ON DATABASE :"database_name" TO :"backup_role";
GRANT USAGE ON SCHEMA public TO :"backup_role";
GRANT SELECT ON ALL TABLES IN SCHEMA public TO :"backup_role";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"backup_role";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO :"backup_role";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO :"backup_role";
SQL
