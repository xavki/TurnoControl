-- Hosting compatibility (phase 5F.1B). Only REMOVES privileges; it never grants anything new.
--
-- Supabase's Postgres image declares, in its init scripts:
--   alter default privileges in schema public grant all on functions to postgres, anon, authenticated, service_role;
--   alter default privileges for user supabase_admin in schema public grant all on functions to ... (same roles);
-- so whichever role runs 20261122000000_turno_sync.sql, public.sync_push / public.sync_pull end up with an EXPLICIT
-- EXECUTE grant to anon and service_role. The base migration only revokes from PUBLIC, which does not remove those
-- explicit grants. anon would still be stopped by turno.current_account() (role must be 'authenticated'), but the
-- intended model is "anon has no access at all", and 10_sync_security_test.sql asserts exactly that.
--
-- Idempotent: revoking a privilege that is not held is a no-op.

revoke all on function public.sync_push(uuid, jsonb), public.sync_pull(bigint, int) from anon, service_role;
grant execute on function public.sync_push(uuid, jsonb), public.sync_pull(bigint, int) to authenticated;

-- Defence in depth for the private schema: no API role may use it directly, whatever default privileges exist.
revoke all on schema turno from anon, authenticated, service_role;
revoke all on all tables in schema turno from anon, authenticated, service_role;
revoke all on all sequences in schema turno from anon, authenticated, service_role;
revoke all on all functions in schema turno from anon, authenticated, service_role;
