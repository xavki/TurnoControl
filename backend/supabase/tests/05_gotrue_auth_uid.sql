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
