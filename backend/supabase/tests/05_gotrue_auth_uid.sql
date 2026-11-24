-- TESTS AGAINST THE SUPABASE POSTGRES IMAGE ONLY (run-local-tests.sh with TURNO_SUPABASE_IMAGE=1).
-- The image's init script defines auth.uid() reading only the legacy GUC request.jwt.claim.sub. In a real stack GoTrue's
-- migration 20220224000811_update_auth_functions replaces it with this definition (identical to the local shim), which
-- also reads request.jwt.claims, the only GUC PostgREST sets with PGRST_DB_USE_LEGACY_GUCS=false.
create or replace function auth.uid() returns uuid language sql stable as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim.sub', true), ''),
    (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')
  )::uuid
$$;
-- The image's init scripts already create auth.refresh_tokens and auth.audit_log_entries; GoTrue's migrations create
-- auth.sessions and auth.flow_state and add refresh_tokens.session_id. Minimal stand-ins for those (same keys and
-- foreign keys as GoTrue v2.198.0) so public.delete_my_account() can be tested without GoTrue.
create table if not exists auth.sessions (id uuid primary key, user_id uuid not null references auth.users(id) on delete cascade, not_after timestamptz);
create table if not exists auth.flow_state (id uuid primary key, user_id uuid);
alter table auth.refresh_tokens add column if not exists session_id uuid references auth.sessions(id) on delete cascade;
-- GoTrue enables row-level security WITHOUT policies on its auth tables (v2.198.0); reproduce it.
alter table auth.users enable row level security;
alter table auth.sessions enable row level security;
alter table auth.refresh_tokens enable row level security;
alter table auth.flow_state enable row level security;
alter table auth.audit_log_entries enable row level security;
