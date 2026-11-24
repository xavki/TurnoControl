-- Re-runnable RPC privilege baseline (phase 5F.1D). NOT a ledgered migration: scripts/migrate.sh applies it after every
-- run, and scripts/restore-drill.sh after every restore (pg_restore recreates functions as supabase_admin, and the
-- Supabase image's default privileges then grant EXECUTE on them to anon/service_role again).
-- Only REMOVES privileges, except the explicit EXECUTE grant of the allowlisted RPCs to `authenticated`.
do $$
declare f record;
begin
  -- Every public function owned by turno_owner: nobody but `authenticated` (below) may execute it.
  for f in select p.oid::regprocedure as sig from pg_proc p join pg_namespace n on n.oid = p.pronamespace
           where n.nspname = 'public' and pg_get_userbyid(p.proowner) = 'turno_owner' loop
    execute format('revoke all on function %s from public, anon, service_role', f.sig);
  end loop;
  -- Allowlist of RPCs the app may call (only those that exist in this database).
  for f in select p.oid::regprocedure as sig from pg_proc p join pg_namespace n on n.oid = p.pronamespace
           where n.nspname = 'public' and pg_get_userbyid(p.proowner) = 'turno_owner'
             and p.proname in ('sync_push', 'sync_pull', 'delete_my_account') loop
    execute format('grant execute on function %s to authenticated', f.sig);
  end loop;
end $$;
-- Private schemas: no API role may use them, whatever default privileges exist.
revoke all on schema turno from anon, authenticated, service_role;
revoke all on all tables in schema turno from anon, authenticated, service_role;
revoke all on all functions in schema turno from public, anon, authenticated, service_role;
do $$
begin
  if exists (select 1 from pg_namespace where nspname = 'turno_ops') then
    execute 'revoke all on schema turno_ops from public, anon, authenticated, service_role';
    execute 'revoke all on all tables in schema turno_ops from public, anon, authenticated, service_role';
    execute 'revoke all on all functions in schema turno_ops from public, anon, authenticated, service_role';
  end if;
end $$;
