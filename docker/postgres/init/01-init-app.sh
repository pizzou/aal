#!/usr/bin/env bash
set -euo pipefail

# Runs only for a newly initialized PostgreSQL volume. On an existing DB,
# a DBA must reconcile table ownership before switching Flyway identities.
: "${POSTGRES_USER:?Postgres bootstrap user is required}"
: "${POSTGRES_DB:?Database name is required}"
: "${SPRING_DATASOURCE_USERNAME:=aal_app}"
: "${SPRING_DATASOURCE_PASSWORD:?Application role password is required}"
: "${SPRING_FLYWAY_USERNAME:=aal_migrator}"
: "${SPRING_FLYWAY_PASSWORD:?Migration role password is required}"
: "${BACKUP_DB_USERNAME:=aal_backup}"
: "${BACKUP_DB_PASSWORD:?Backup role password is required}"

if [[ "$SPRING_DATASOURCE_USERNAME" == "$SPRING_FLYWAY_USERNAME" ||
      "$SPRING_DATASOURCE_USERNAME" == "$BACKUP_DB_USERNAME" ||
      "$SPRING_FLYWAY_USERNAME" == "$BACKUP_DB_USERNAME" ]]; then
  echo 'The application, migration and backup roles must be different' >&2
  exit 1
fi

# psql does not expand :variables inside DO $$ blocks. SELECT format() with
# \gexec safely quotes role identifiers and password literals separately.
psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=app_role="$SPRING_DATASOURCE_USERNAME" \
  --set=app_password="$SPRING_DATASOURCE_PASSWORD" \
  --set=migrator_role="$SPRING_FLYWAY_USERNAME" \
  --set=migrator_password="$SPRING_FLYWAY_PASSWORD" \
  --set=backup_role="$BACKUP_DB_USERNAME" \
  --set=backup_password="$BACKUP_DB_PASSWORD" \
  --set=database_name="$POSTGRES_DB" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'app_role')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=:'app_role') \gexec
SELECT format('CREATE ROLE %I LOGIN', :'migrator_role')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=:'migrator_role') \gexec
SELECT format('CREATE ROLE %I LOGIN', :'backup_role')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=:'backup_role') \gexec

SELECT format('ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'app_role', :'app_password') \gexec
SELECT format('ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'migrator_role', :'migrator_password') \gexec
SELECT format('ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE REPLICATION BYPASSRLS PASSWORD %L', :'backup_role', :'backup_password') \gexec

-- Ensure trusted extensions exist before non-superuser Flyway migrations.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

GRANT CONNECT ON DATABASE :"database_name" TO :"app_role", :"migrator_role", :"backup_role";
GRANT CREATE ON DATABASE :"database_name" TO :"migrator_role";
GRANT USAGE, CREATE ON SCHEMA public TO :"migrator_role";
GRANT USAGE ON SCHEMA public TO :"app_role", :"backup_role";

-- Permissions on current tables plus FUTURE tables owned by the migrator.
-- Flyway creates schema objects exclusively as the migrator role.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"app_role";
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO :"app_role";
GRANT SELECT ON ALL TABLES IN SCHEMA public TO :"backup_role";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"backup_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO :"app_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA public
  GRANT SELECT ON TABLES TO :"backup_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO :"backup_role";
SQL
