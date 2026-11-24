-- Restore-drill integrity checks (disposable restored database only; run as supabase_admin). One `check|t/f` line each.
-- Complements verify_deployment.sql with data-level properties: no orphans, healthy B-tree indexes, and RLS/RPC still
-- returning to each account exactly its own rows.
\pset footer off
create extension if not exists amcheck;
select 'sin registros huérfanos (user_id sin cuenta)' as check_name,
  not exists (select 1 from turno.records r left join auth.users u on u.id = r.user_id where u.id is null)
  and not exists (select 1 from turno.record_history r left join auth.users u on u.id = r.user_id where u.id is null)
  and not exists (select 1 from turno.applied_mutations r left join auth.users u on u.id = r.user_id where u.id is null) as ok
union all select 'seq únicos por cuenta', not exists (select 1 from turno.records group by user_id, seq having count(*) > 1)
union all select 'user_state.last_seq >= max(seq) de cada cuenta', not exists (select 1 from turno.records r join turno.user_state s using (user_id) group by r.user_id, s.last_seq having max(r.seq) > s.last_seq)
union all select 'índices B-tree de turno íntegros (amcheck)', (select bool_and(true) from (select bt_index_check(c.oid, true) from pg_index i join pg_class c on c.oid = i.indexrelid
  join pg_namespace n on n.oid = c.relnamespace join pg_am a on a.oid = c.relam where n.nspname = 'turno' and a.amname = 'btree') x);

-- Functional RLS/RPC check: for every account with data, sync_pull under ITS verified claims returns exactly its rows.
create temporary table restore_expected as select user_id, count(*) as n from turno.records group by user_id;
grant select on restore_expected to authenticated;
do $$
declare acc record; got int; bad int := 0; total int := 0;
begin
  for acc in select user_id, n from restore_expected loop
    total := total + 1;
    perform set_config('request.jwt.claims', json_build_object('sub', acc.user_id, 'role', 'authenticated', 'exp', 9999999999)::text, true);
    execute 'set local role authenticated';
    select jsonb_array_length(public.sync_pull(0, 1000)->'changes') into got;
    execute 'reset role';
    if got <> acc.n then bad := bad + 1; end if;
  end loop;
  perform set_config('turno.restore_rls_bad', bad::text, false);
  perform set_config('turno.restore_rls_total', total::text, false);
end $$;
select 'RLS/RPC tras restaurar: cada cuenta ve exactamente lo suyo (' || current_setting('turno.restore_rls_total') || ' cuentas)',
  current_setting('turno.restore_rls_bad') = '0' and current_setting('turno.restore_rls_total')::int > 0;
