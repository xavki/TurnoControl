-- Deployment hardening for the TurnoControl stack (phase 5F.1B). Applied by scripts/migrate.sh BEFORE the migrations.
-- Only REMOVES privileges. Idempotent.
--
-- Supabase's Postgres image (init script 00000000000000-initial-schema.sql) runs:
--   grant anon, authenticated, service_role, supabase_admin to authenticator;
-- authenticator is the role PostgREST logs in as; it switches to whatever role the verified JWT names. Membership in
-- service_role and supabase_admin means that anyone holding the JWT signing secret could forge a token that turns an API
-- request into a superuser session (supabase_admin) or an RLS-bypassing one (service_role).
-- Observed in 5F.1C stage 0 on supabase/postgres:17.6.1.136: authenticator IS a member of service_role (granted by
-- supabase_admin) but NOT of supabase_admin. The supabase_admin revoke is therefore a defensive no-op today (it logs a
-- WARNING) and stays in case a future image restores that grant; the assertion below covers both roles.
-- TurnoControl never needs either through the public API: the app only uses anon (before sign-in) and authenticated,
-- and administration happens over SSH inside the server. So the API connection is limited to those two roles.
--
-- Reverting (only if a future, approved need appears): grant service_role to authenticator;
revoke supabase_admin from authenticator;
revoke service_role from authenticator;

-- A membership REVOKE by a role that is not the grantor only emits a WARNING; fail the transaction instead, so
-- scripts/migrate.sh never records this file as applied without its effect.
do $$
begin
  if pg_has_role('authenticator', 'supabase_admin', 'member') or pg_has_role('authenticator', 'service_role', 'member') then
    raise exception 'authenticator sigue pudiendo asumir supabase_admin o service_role';
  end if;
end $$;
