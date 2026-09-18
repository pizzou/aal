-- A random, unguessable token per shipment is the public tracking mechanism: knowing
-- the token IS the authorization (like a share link), so this deliberately does NOT
-- require the customer to log in or know which tenant/company the shipment belongs
-- to. This is the third narrow, documented exception to "everything goes through
-- TenantContext" — the other two are the pre-tenant auth lookup (AuthService) and the
-- BYPASSRLS backup role. All three are narrow, single-purpose, and documented,
-- unlike a general bypass that would undermine the RLS work verified throughout
-- this project.

ALTER TABLE shipments
    ADD COLUMN IF NOT EXISTS tracking_token UUID NOT NULL DEFAULT gen_random_uuid();

-- The DEFAULT above exists only to backfill EXISTING rows at migration time. Going
-- forward, the application always supplies this value explicitly (see Shipment.java)
-- rather than relying on Hibernate correctly re-reading a DB-generated default — so
-- the default is dropped here to make that intent explicit and avoid the two
-- mechanisms silently disagreeing later.
ALTER TABLE shipments ALTER COLUMN tracking_token DROP DEFAULT;

-- Uniqueness matters here for the same reason a password needs to be unguessable —
-- this token IS the access control. A btree unique index also makes the public
-- lookup query fast regardless of table size.
CREATE UNIQUE INDEX IF NOT EXISTS idx_shipments_tracking_token_unique ON shipments(tracking_token);
