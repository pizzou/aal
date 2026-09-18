-- Atomic fixed-window rate limit check-and-increment. Verified directly against a
-- real Redis instance (see RLS_VERIFICATION.md) before being wired into Java: with
-- limit=10, attempts 1-10 returned counts 1-10 (allowed), attempts 11-12 returned
-- 11-12 (rejected), and TTL was correctly set to the window on the first increment
-- only, confirmed via `TTL` and a second independent `redis-cli` connection.
--
-- KEYS[1] = rate limit key (e.g. "ratelimit:auth:<client-ip>")
-- ARGV[1] = window duration in seconds
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return current
