-- Retention tests (phase 5F.1D). Synthetic accounts only. Run by run-local-tests.sh after 20_account_deletion_test.sql
-- (reuses test.ok / test.fails / test.m / test.claims). Time passing is simulated by backdating timestamps.
\set ON_ERROR_STOP 1
\set QUIET 1
\o /dev/null
-- R: the account whose devices go offline. S: a bystander whose rows are recent and must never be purged.
insert into auth.users (id, email) values ('00000000-0000-4000-8000-0000000000f1', 'rita@example.invalid'), ('00000000-0000-4000-8000-0000000000f2', 'sara@example.invalid');
insert into auth.sessions (id, user_id) values ('5e550000-0000-4000-8000-0000000000f1', '00000000-0000-4000-8000-0000000000f1'), ('5e550000-0000-4000-8000-0000000000f2', '00000000-0000-4000-8000-0000000000f2');
create function test.pushr(dev text, op text, key text, oper text, payload text, base bigint) returns text language sql as $$
  select (public.sync_push(dev::uuid, jsonb_build_array(test.m(op, 'SCHEDULED_DAY', key, oper, payload, base))))->0->>'status'
$$;
grant execute on function test.pushr(text, text, text, text, text, bigint) to public;
create table test.cursor_store(name text primary key, value bigint);
grant all on test.cursor_store to public;

-- ---------- Initial state, both devices of R in sync (cursor c0)
set role authenticated;
select test.claims('00000000-0000-4000-8000-0000000000f1', '5e550000-0000-4000-8000-0000000000f1', 5);
select test.pushr('d1000000-0000-4000-8000-000000000001', 'f1000000-0000-4000-8000-000000000001', '2027-10-01', 'UPSERT', '1|2027-10-01|FREE|MANUAL|', null);
select test.pushr('d1000000-0000-4000-8000-000000000001', 'f1000000-0000-4000-8000-000000000002', '2027-10-02', 'UPSERT', '1|2027-10-02|FREE|MANUAL|', null);
select test.pushr('d1000000-0000-4000-8000-000000000001', 'f1000000-0000-4000-8000-000000000003', '2027-10-03', 'UPSERT', '1|2027-10-03|FREE|MANUAL|', null);
insert into test.cursor_store select 'c0', (public.sync_pull(0, 1000)->>'next_cursor')::bigint;
-- Device 1 goes offline at c0. Device 2 keeps working: edit X, delete Y, delete+recreate Z, create W.
select test.pushr('d2000000-0000-4000-8000-000000000002', 'f2000000-0000-4000-8000-000000000001', '2027-10-01', 'UPSERT', '1|2027-10-01|VACATION|MANUAL|', 1);
select test.pushr('d2000000-0000-4000-8000-000000000002', 'f2000000-0000-4000-8000-000000000002', '2027-10-02', 'DELETE', null, 1);
select test.pushr('d2000000-0000-4000-8000-000000000002', 'f2000000-0000-4000-8000-000000000003', '2027-10-03', 'DELETE', null, 1);
select test.pushr('d2000000-0000-4000-8000-000000000002', 'f2000000-0000-4000-8000-000000000004', '2027-10-03', 'UPSERT', '1|2027-10-03|SICK_LEAVE|MANUAL|', 2);
select test.pushr('d2000000-0000-4000-8000-000000000002', 'f2000000-0000-4000-8000-000000000005', '2027-10-04', 'UPSERT', '1|2027-10-04|FREE|MANUAL|', null);
-- S writes too (stays recent).
select test.claims('00000000-0000-4000-8000-0000000000f2', '5e550000-0000-4000-8000-0000000000f2', 5);
select test.pushr('d3000000-0000-4000-8000-000000000003', 'f3000000-0000-4000-8000-000000000001', '2027-10-01', 'UPSERT', '1|2027-10-01|FREE|MANUAL|', null);
select test.pushr('d3000000-0000-4000-8000-000000000003', 'f3000000-0000-4000-8000-000000000002', '2027-10-01', 'UPSERT', '1|2027-10-01|VACATION|MANUAL|', 1);
reset role;
insert into auth.audit_log_entries (id, payload, created_at) values
  ('a0d10000-0000-4000-8000-0000000000f1', '{"actor_id":"00000000-0000-4000-8000-0000000000f1","action":"login"}', now() - interval '200 days'),
  ('a0d10000-0000-4000-8000-0000000000f2', '{"actor_id":"00000000-0000-4000-8000-0000000000f2","action":"login"}', now() - interval '1 day');

-- ---------- Guards and access
select test.ok(not has_function_privilege('authenticated', 'turno_ops.purge_retention(int,boolean)', 'execute')
  and not has_function_privilege('anon', 'turno_ops.purge_retention(int,boolean)', 'execute')
  and not has_function_privilege('service_role', 'turno_ops.purge_retention(int,boolean)', 'execute')
  and not has_function_privilege('turno_owner', 'turno_ops.purge_retention(int,boolean)', 'execute'), 'retención: ningún rol de la API (ni turno_owner) puede ejecutar la purga');
select test.ok((select not prosecdef from pg_proc where oid = 'turno_ops.purge_retention(int,boolean)'::regprocedure), 'retención: la purga es SECURITY INVOKER (solo el operador)');
select test.fails('select turno_ops.purge_retention(7, true)', '22023', 'retención: menos de 30 días, rechazado');
select test.fails('select turno_ops.purge_retention(null, true)', '22023', 'retención: sin días, rechazado');

-- ---------- Recent rows: nothing is eligible
create temp table snap(name text primary key, value text);
insert into snap select 'records', md5(string_agg(user_id::text || entity || record_key || version || seq || deleted || coalesce(payload, '~') || operation_id, ',' order by user_id, entity, record_key)) from turno.records;
insert into snap select 'user_state', md5(string_agg(user_id::text || last_seq, ',' order by user_id)) from turno.user_state;
select test.ok((select (r->>'history')::int = 0 and (r->>'mutations')::int = 0 from (select turno_ops.purge_retention(90) r) x), 'retención: con todo reciente, nada es elegible');

-- ---------- Time passes for R only: backdate R's rows by 120 days
update turno.record_history set replaced_at = now() - interval '120 days' where user_id = '00000000-0000-4000-8000-0000000000f1';
update turno.applied_mutations set applied_at = now() - interval '120 days' where user_id = '00000000-0000-4000-8000-0000000000f1';
create temp table dry(out jsonb);
insert into dry select turno_ops.purge_retention(90);
select test.ok((select out->>'mode' = 'DRY_RUN' and (out->>'history')::int = 4 and (out->>'mutations')::int = 4 and (out->>'mutations_kept_current')::int = 4 and (out->>'audit')::int = 1 and (out->>'accounts')::int = 1 from dry),
  'retención: simulación con recuentos exactos (4 de historial, 4 mutaciones, 4 actuales conservadas, 1 auditoría, 1 cuenta)');
select test.ok((select count(*) = 4 from turno.record_history where user_id = '00000000-0000-4000-8000-0000000000f1'), 'retención: la simulación no borra nada');

-- ---------- Interrupted purge: an error inside the transaction leaves everything in place
select test.fails('select turno_ops.purge_retention(90, true), 1/0', '22012', 'retención: una purga interrumpida por un error se revierte');
select test.ok((select count(*) = 4 from turno.record_history where user_id = '00000000-0000-4000-8000-0000000000f1') and (select count(*) = 0 from turno_ops.retention_runs), 'retención: tras la interrupción no se borró ni registró nada');

-- ---------- Apply
create temp table applied(out jsonb);
insert into applied select turno_ops.purge_retention(90, true);
select test.ok((select a.out->>'history' = d.out->>'history' and a.out->>'mutations' = d.out->>'mutations' and a.out->>'audit' = d.out->>'audit' and a.out->>'mode' = 'APPLIED' from applied a, dry d),
  'retención: lo aplicado coincide exactamente con la simulación');
select test.ok((select value from snap where name = 'records') = (select md5(string_agg(user_id::text || entity || record_key || version || seq || deleted || coalesce(payload, '~') || operation_id, ',' order by user_id, entity, record_key)) from turno.records)
  and (select value from snap where name = 'user_state') = (select md5(string_agg(user_id::text || last_seq, ',' order by user_id)) from turno.user_state), 'retención: turno.records (con lápidas) y user_state quedan idénticos');
select test.ok((select count(*) = 2 from turno.records where user_id = '00000000-0000-4000-8000-0000000000f1' and record_key in ('2027-10-02') and deleted) or
  (select count(*) = 1 from turno.records where user_id = '00000000-0000-4000-8000-0000000000f1' and record_key = '2027-10-02' and deleted), 'retención: la lápida de Y sigue existiendo');
select test.ok((select count(*) = 4 from turno.applied_mutations a join turno.records r on r.user_id = a.user_id and r.operation_id = a.operation_id where a.user_id = '00000000-0000-4000-8000-0000000000f1'),
  'retención: se conservan las mutaciones de la versión actual de cada registro');
select test.ok((select count(*) = 1 from turno.record_history where user_id = '00000000-0000-4000-8000-0000000000f2') and (select count(*) = 2 from turno.applied_mutations where user_id = '00000000-0000-4000-8000-0000000000f2'),
  'retención: la cuenta S (reciente) no pierde nada');
select test.ok(not exists (select 1 from auth.audit_log_entries where id = 'a0d10000-0000-4000-8000-0000000000f1') and exists (select 1 from auth.audit_log_entries where id = 'a0d10000-0000-4000-8000-0000000000f2'),
  'retención: solo se purga la auditoría antigua');
select test.ok((select count(*) = 1 and bool_and(history = 4 and mutations = 4) from turno_ops.retention_runs)
  and not exists (select 1 from information_schema.columns where table_schema = 'turno_ops' and table_name = 'retention_runs' and data_type in ('uuid', 'text', 'character varying', 'jsonb', 'json')),
  'retención: el registro de ejecuciones guarda solo recuentos');
select test.ok((select (r->>'history')::int = 0 and (r->>'mutations')::int = 0 and (r->>'audit')::int = 0 from (select turno_ops.purge_retention(90, true) r) x), 'retención: repetir la purga no borra nada más');

-- ---------- Device 1 comes back after the purge, from cursor c0
set role authenticated;
select test.claims('00000000-0000-4000-8000-0000000000f1', '5e550000-0000-4000-8000-0000000000f1', 5);
create temp table back(page jsonb);
insert into back select public.sync_pull((select value from test.cursor_store where name = 'c0'), 1000);
select test.ok((select jsonb_array_length(page->'changes') = 4 from back), 'offline: el dispositivo recibe los 4 cambios posteriores a su cursor');
select test.ok((select count(*) = 1 from back, jsonb_array_elements(page->'changes') c where c->>'key' = '2027-10-01' and c->>'payload' = '1|2027-10-01|VACATION|MANUAL|'), 'offline: recibe la edición de X');
select test.ok((select count(*) = 1 from back, jsonb_array_elements(page->'changes') c where c->>'key' = '2027-10-02' and (c->>'deleted')::boolean and c->'payload' = 'null'::jsonb), 'offline: recibe la lápida de Y (no se pierde el borrado)');
select test.ok((select count(*) = 1 from back, jsonb_array_elements(page->'changes') c where c->>'key' = '2027-10-03' and c->>'payload' = '1|2027-10-03|SICK_LEAVE|MANUAL|' and (c->>'version')::int = 3), 'offline: recibe Z recreado (versión 3, solo fecha y estado)');
select test.ok((select count(*) = 1 from back, jsonb_array_elements(page->'changes') c where c->>'key' = '2027-10-04'), 'offline: recibe W, creado mientras estaba desconectado');
-- Its stale outbox (written at c0) must never resurrect or overwrite.
select test.ok(test.pushr('d1000000-0000-4000-8000-000000000001', 'f1000000-0000-4000-8000-000000000010', '2027-10-02', 'UPSERT', '1|2027-10-02|WORK|MANUAL|09:00+0-17:00+0:N', 1) = 'CONFLICT', 'offline: editar Y (borrado) con base antigua = CONFLICT, sin resurrección');
select test.ok(test.pushr('d1000000-0000-4000-8000-000000000001', 'f1000000-0000-4000-8000-000000000011', '2027-10-01', 'UPSERT', '1|2027-10-01|FREE|MANUAL|', 1) = 'CONFLICT', 'offline: editar X con base antigua = CONFLICT, sin sobrescribir');
select test.ok(test.pushr('d1000000-0000-4000-8000-000000000001', 'f1000000-0000-4000-8000-000000000001', '2027-10-01', 'UPSERT', '1|2027-10-01|FREE|MANUAL|', null) = 'CONFLICT', 'offline: reenviar su operación original ya purgada = CONFLICT (nunca se duplica)');
select test.ok(test.pushr('d2000000-0000-4000-8000-000000000002', 'f2000000-0000-4000-8000-000000000005', '2027-10-04', 'UPSERT', '1|2027-10-04|FREE|MANUAL|', null) = 'APPLIED', 'offline: reintentar la última operación de un registro sigue siendo idempotente (APPLIED)');
reset role;
select test.ok((select count(*) = 1 from turno.records where user_id = '00000000-0000-4000-8000-0000000000f1' and record_key = '2027-10-02' and deleted) and
  (select payload = '1|2027-10-01|VACATION|MANUAL|' and version = 2 from turno.records where user_id = '00000000-0000-4000-8000-0000000000f1' and record_key = '2027-10-01') and
  (select version = 1 from turno.records where user_id = '00000000-0000-4000-8000-0000000000f1' and record_key = '2027-10-04'),
  'offline: el estado del servidor no cambió por el outbox obsoleto');

-- ---------- Offline 1 / 30 / 89 / 91 / 400 days: pull from the old cursor is always complete
do $$
declare d int; ok_all boolean := true; got int; c bigint;
begin
  foreach d in array array[1, 30, 89, 91, 400] loop
    update turno.record_history set replaced_at = now() - make_interval(days => d) where user_id = '00000000-0000-4000-8000-0000000000f1';
    update turno.applied_mutations set applied_at = now() - make_interval(days => d) where user_id = '00000000-0000-4000-8000-0000000000f1';
    perform turno_ops.purge_retention(90, true);
    select value into c from test.cursor_store where name = 'c0';
    perform set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-0000000000f1","role":"authenticated","exp":9999999999}', true);
    execute 'set local role authenticated';
    select jsonb_array_length(public.sync_pull(c, 1000)->'changes') into got;
    execute 'reset role';
    if got <> 4 then ok_all := false; end if;
  end loop;
  perform set_config('turno.offline_ok', ok_all::text, false);
end $$;
select test.ok(current_setting('turno.offline_ok') = 'true', 'offline: con 1, 30, 89, 91 y 400 días, el pull desde el cursor antiguo devuelve siempre los 4 cambios');
\o
select 'RESULTADO RETENCIÓN: ' || count(*) filter (where ok and (name like 'retención:%' or name like 'offline:%')) || ' superadas, ' || count(*) filter (where not ok and (name like 'retención:%' or name like 'offline:%')) || ' fallidas' from test.results;
