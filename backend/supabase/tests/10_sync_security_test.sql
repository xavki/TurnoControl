-- Security and protocol tests for the synchronization backend. Synthetic accounts only. Run by run-local-tests.sh.
\set ON_ERROR_STOP 1
\set QUIET 1
\o /dev/null
create schema test;
create table test.results (n serial primary key, name text not null, ok boolean not null);
create function test.ok(condition boolean, name text) returns void language plpgsql as $$
begin
  insert into test.results(name, ok) values (name, coalesce(condition, false));
  if not coalesce(condition, false) then raise exception 'FALLO: %', name; end if;
end $$;
-- Expects `statement` to fail with SQLSTATE `code`.
create function test.fails(statement text, code text, name text) returns void language plpgsql as $$
begin
  execute statement;
  perform test.ok(false, name || ' (no falló)');
exception when others then
  if sqlstate = code then perform test.ok(true, name); else raise exception 'FALLO: % (SQLSTATE % en vez de %: %)', name, sqlstate, code, sqlerrm; end if;
end $$;
create function test.m(op_id text, entity text, key text, op text, payload text, base bigint) returns jsonb language sql as $$
  select jsonb_build_object('operation_id', op_id, 'entity', entity, 'key', key, 'op', op, 'payload', payload, 'base_version', base, 'data_format', 1)
$$;
create function test.push1(op_id text, entity text, key text, op text, payload text, base bigint) returns jsonb language sql as $$
  select (public.sync_push('11111111-1111-4111-8111-111111111111', jsonb_build_array(test.m(op_id, entity, key, op, payload, base))))->0
$$;
grant usage on schema test to public;
grant all on all tables in schema test to public;
grant all on all sequences in schema test to public;
grant execute on all functions in schema test to public;

insert into auth.users values ('00000000-0000-4000-8000-00000000000a', 'ana@example.invalid'), ('00000000-0000-4000-8000-00000000000b', 'bea@example.invalid');

-- ---------- Static security properties ----------
select test.ok((select bool_and(relrowsecurity and relforcerowsecurity) from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'turno' and c.relkind = 'r'), 'RLS activada y forzada en todas las tablas de turno');
select test.ok((select count(*) = 4 from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'turno' and c.relkind = 'r'), 'cuatro tablas en turno');
select test.ok((select bool_and(proconfig @> array['search_path=""'] or proconfig @> array['search_path=']) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where p.prosecdef and n.nspname in ('public','turno')), 'toda función SECURITY DEFINER fija search_path vacío');
select test.ok((select count(*) = 2 from pg_proc p join pg_namespace n on n.oid = p.pronamespace where p.prosecdef and n.nspname in ('public','turno')), 'solo dos funciones SECURITY DEFINER (sync_push y sync_pull)');
select test.ok(not has_table_privilege('authenticated', 'turno.records', 'select,insert,update,delete'), 'authenticated sin privilegios sobre turno.records');
select test.ok(not has_table_privilege('anon', 'turno.records', 'select'), 'anon sin privilegios sobre turno.records');
select test.ok(not has_function_privilege('anon', 'public.sync_pull(bigint,int)', 'execute'), 'anon no puede ejecutar sync_pull');
select test.ok(not pg_has_role('authenticated', 'turno_owner', 'member'), 'authenticated no es miembro de turno_owner');
select test.ok(not pg_has_role('authenticator', 'turno_owner', 'member') and not pg_has_role('authenticator', 'service_role', 'member'), 'la conexión de la API (authenticator) no puede asumir turno_owner ni service_role');

-- ---------- Anonymous and invalid sessions ----------
set role anon;
select test.fails('select public.sync_pull(0, 10)', '42501', 'anónimo: sync_pull denegado');
select test.fails('select public.sync_push(''11111111-1111-4111-8111-111111111111'', ''[]'')', '42501', 'anónimo: sync_push denegado');
select test.fails('select * from turno.records', '42501', 'anónimo: lectura directa denegada');
reset role;

set role authenticated;
select set_config('request.jwt.claims', '', false);
select test.fails('select public.sync_pull(0, 10)', '28000', 'sin sesión: rechazado');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-00000000000a","role":"authenticated","exp":1000}', false);
select test.fails('select public.sync_pull(0, 10)', '28000', 'sesión caducada: rechazada');
select set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-00000000000a","role":"anon","exp":9999999999}', false);
select test.fails('select public.sync_pull(0, 10)', '28000', 'rol del token distinto de authenticated: rechazado');
select set_config('request.jwt.claims', '{"sub":"x''; drop table turno.records; --","role":"authenticated","exp":9999999999}', false);
select test.fails('select public.sync_pull(0, 10)', '28000', 'sujeto no UUID (inyección): rechazado');
select test.fails('select turno.current_account()', '42501', 'funciones internas inaccesibles');

-- ---------- Account A: protocol ----------
select set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-00000000000a","role":"authenticated","exp":9999999999}', false);
select test.ok(test.push1('a0000000-0000-4000-8000-000000000001', 'SCHEDULED_DAY', '2026-12-02', 'UPSERT', '1|2026-12-02|WORK|MANUAL|08:00+0-12:00+0:N,22:00+0-06:00+1:E', null)
  = '{"operation_id":"a0000000-0000-4000-8000-000000000001","status":"APPLIED","version":1,"seq":1}'::jsonb, 'A: alta aplicada (turno partido, nocturno y extra)');
select test.ok(test.push1('a0000000-0000-4000-8000-000000000001', 'SCHEDULED_DAY', '2026-12-02', 'UPSERT', '1|2026-12-02|WORK|MANUAL|08:00+0-12:00+0:N,22:00+0-06:00+1:E', null)
  ->> 'status' = 'APPLIED', 'A: operación repetida devuelve el resultado guardado');
select test.ok((public.sync_pull(0, 100)->'changes'->0->>'version')::int = 1 and jsonb_array_length(public.sync_pull(0, 100)->'changes') = 1, 'A: repetición idempotente, una sola versión');
select test.ok(test.push1('a0000000-0000-4000-8000-000000000002', 'SCHEDULED_DAY', '2026-12-02', 'UPSERT', '1|2026-12-02|FREE|MANUAL|', null) ->> 'status' = 'CONFLICT', 'A: base obsoleta (nula) = conflicto');
select test.ok(test.push1('a0000000-0000-4000-8000-000000000003', 'SCHEDULED_DAY', '2026-12-02', 'UPSERT', '1|2026-12-02|FREE|MANUAL|', 7) ->> 'status' = 'CONFLICT', 'A: base futura (fuera de orden) = conflicto');
select test.ok((select payload from jsonb_to_recordset(public.sync_pull(0, 100)->'changes') as x(payload text)) like '%22:00+0-06:00+1:E', 'A: el conflicto no sobrescribió nada');
select test.ok(test.push1('a0000000-0000-4000-8000-000000000004', 'SCHEDULED_DAY', '2026-12-02', 'UPSERT', '1|2026-12-02|SICK_LEAVE|MANUAL|', 1)
  = '{"operation_id":"a0000000-0000-4000-8000-000000000004","status":"APPLIED","version":2,"seq":2}'::jsonb, 'A: baja médica (fecha y estado) aplicada sobre versión 1');
select test.ok(test.push1('a0000000-0000-4000-8000-000000000005', 'SCHEDULED_DAY', '2026-12-02', 'DELETE', null, 2) ->> 'status' = 'APPLIED', 'A: borrado real = lápida');
select test.ok((public.sync_pull(2, 100)->'changes'->0) @> '{"deleted":true,"payload":null,"version":3}', 'A: la lápida no lleva contenido');
select test.ok(test.push1('a0000000-0000-4000-8000-000000000006', 'SCHEDULED_DAY', '2026-12-03', 'UPSERT', '1|2026-12-03|VACATION|MANUAL|', null) ->> 'status' = 'APPLIED', 'A: vacaciones');
select test.ok(test.push1('a0000000-0000-4000-8000-000000000007', 'PAY_RULE', 'EXTRA_HOUR@2026-01-01', 'UPSERT', '1|EXTRA_HOUR|2026-01-01|-|15.5|EUR', null) ->> 'status' = 'APPLIED', 'A: tarifa');
select test.ok(test.push1('a0000000-0000-4000-8000-000000000008', 'PROFILE', 'profile', 'UPSERT', '1|Perfil ficticio %7C uno||Europe/Madrid', null) ->> 'status' = 'APPLIED', 'A: perfil con separador escapado');
select test.ok(test.push1('a0000000-0000-4000-8000-000000000009', 'CONTRACT', 'contract', 'UPSERT', '1|FULL|2400|WEEK|2026-01-01|-', null) ->> 'status' = 'APPLIED', 'A: contrato');
select test.ok(test.push1('a0000000-0000-4000-8000-00000000000a', 'PAYSLIP', '2026-11', 'UPSERT', '1|2026-11|1500|-', null) ->> 'status' = 'APPLIED', 'A: nómina (formato futuro admitido por el protocolo)');

-- Strict payloads, sick-leave privacy, malformed keys and injection attempts: REJECTED and remembered, nothing stored.
do $$
declare
  cases text[][] := array[
    array['SCHEDULED_DAY','2026-12-10','1|2026-12-10|SICK_LEAVE|OCR_REVIEWED|','baja con procedencia adicional'],
    array['SCHEDULED_DAY','2026-12-10','1|2026-12-10|SICK_LEAVE|MANUAL|08:00+0-12:00+0:N','baja con horas'],
    array['SCHEDULED_DAY','2026-12-10','1|2026-12-10|SICK_LEAVE|MANUAL||gripe','baja con campo extra (diagnóstico)'],
    array['SCHEDULED_DAY','2026-12-10','1|2026-12-10|SICK_LEAVE|MANUAL|diagnostico=gripe','baja con texto libre'],
    array['SCHEDULED_DAY','2026-13-40','1|2026-13-40|FREE|MANUAL|','fecha imposible'],
    array['SCHEDULED_DAY','2026-12-10','1|2026-12-11|FREE|MANUAL|','clave distinta de la fecha'],
    array['SCHEDULED_DAY','x''); drop table turno.records; --','1|x|FREE|MANUAL|','clave con inyección SQL'],
    array['SCHEDULED_DAY','2026-12-10','1|2026-12-10|WORK|MANUAL|08:00+0-','intervalo abierto'],
    array['SCHEDULED_DAY','2026-12-10','1|2026-12-10|WORK|MANUAL|08:00+0-12:00+0:N,08:00+0-12:00+0:N','intervalos duplicados'],
    array['SCHEDULED_DAY','2026-12-10','1|2026-12-10|FREE|MANUAL|18:00+0-20:00+0:E','extra en día libre'],
    array['SCHEDULED_DAY','2026-12-10','1|2026-12-10|ON_CALL|MANUAL|','tipo de día desconocido'],
    array['PAY_RULE','BONUS@2026-01-01','1|BONUS|2026-01-01|-|1|EUR','tarifa desconocida'],
    array['PAY_RULE','NIGHT@2026-01-01','1|NIGHT|2026-01-01|-|1.50|EUR','importe no canónico'],
    array['PROFILE','profile','1|Ana|%41|UTC','codificación no admitida'],
    array['CONTRACT','contract','1|FULL|0|WEEK|2026-01-01|-','contrato sin horas'],
    array['PAYSLIP','2026-11','1|2026-11|-|-','nómina vacía'],
    array['CALENDAR_NOTES','x','1|nota','entidad desconocida']];
  i int; r jsonb;
begin
  for i in 1 .. array_length(cases, 1) loop
    r := test.push1(format('b0000000-0000-4000-8000-%s', lpad(i::text, 12, '0')), cases[i][1], cases[i][2], 'UPSERT', cases[i][3], null);
    perform test.ok(r->>'status' = 'REJECTED', 'A: rechazado: ' || cases[i][4]);
  end loop;
end $$;
select test.ok(exists (select 1 from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid = c.relnamespace where n.nspname = 'turno' and c.relname = 'records'), 'A: la tabla sigue existiendo tras los intentos de inyección');

-- Forged ownership and unexpected fields abort the whole batch; nothing of it is applied.
select test.fails($q$select public.sync_push('11111111-1111-4111-8111-111111111111', jsonb_build_array(
  test.m('c0000000-0000-4000-8000-000000000001','SCHEDULED_DAY','2026-12-20','UPSERT','1|2026-12-20|FREE|MANUAL|',null),
  test.m('c0000000-0000-4000-8000-000000000002','SCHEDULED_DAY','2026-12-21','UPSERT','1|2026-12-21|FREE|MANUAL|',null) || '{"user_id":"00000000-0000-4000-8000-00000000000b"}'))$q$,
  '22023', 'A: campo de propietario falsificado = lote rechazado');
select test.fails($q$select public.sync_push('11111111-1111-4111-8111-111111111111', jsonb_build_array(
  test.m('c0000000-0000-4000-8000-000000000003','SCHEDULED_DAY','2026-12-22','UPSERT','1|2026-12-22|SICK_LEAVE|MANUAL|',null) || '{"diagnosis":"gripe"}'))$q$,
  '22023', 'A: campo médico en la operación = lote rechazado');
select test.ok(not exists (select 1 from jsonb_to_recordset(public.sync_pull(0, 1000)->'changes') as x(key text) where key in ('2026-12-20','2026-12-21','2026-12-22')), 'A: los lotes rechazados no dejaron nada');
select test.fails('insert into turno.records values (''00000000-0000-4000-8000-00000000000b'',''PROFILE'',''profile'',1,99,false,1,''1|x||UTC'',gen_random_uuid(),gen_random_uuid())', '42501', 'A: inserción directa (también como B) denegada');
select test.fails('delete from turno.records', '42501', 'A: borrado directo denegado');
select test.fails('update turno.records set payload = null', '42501', 'A: modificación directa denegada');
select test.fails('select public.sync_pull(-1, 10)', '22023', 'A: cursor negativo rechazado');

-- Pagination and cursor semantics.
select test.ok((public.sync_pull(0, 2)->>'has_more')::boolean and jsonb_array_length(public.sync_pull(0, 2)->'changes') = 2
  and (public.sync_pull(0, 2)->>'next_cursor')::bigint = (public.sync_pull(0, 2)->'changes'->1->>'seq')::bigint, 'A: página de 2 con más páginas y cursor en la última secuencia devuelta');
select test.ok(public.sync_pull(0, 2) = public.sync_pull(0, 2), 'A: repetir el mismo cursor devuelve lo mismo');
select test.ok((select array_agg(seq order by seq) = array_agg(seq) from jsonb_to_recordset(public.sync_pull(0, 1000)->'changes') as x(seq bigint)), 'A: orden estable por secuencia');
select test.ok((select count(*) from jsonb_to_recordset(public.sync_pull(0, 1000)->'changes') as x(seq bigint)) = (select count(distinct seq) from jsonb_to_recordset(public.sync_pull(0, 1000)->'changes') as x(seq bigint)), 'A: sin secuencias repetidas');
select test.ok(not (public.sync_pull((public.sync_pull(0, 1000)->>'next_cursor')::bigint, 10)->>'has_more')::boolean
  and jsonb_array_length(public.sync_pull((public.sync_pull(0, 1000)->>'next_cursor')::bigint, 10)->'changes') = 0, 'A: tras el último cursor no hay cambios');

-- Batch atomicity and interrupted transactions.
select test.fails($q$select public.sync_push('11111111-1111-4111-8111-111111111111', jsonb_build_array(
  test.m('d0000000-0000-4000-8000-000000000001','SCHEDULED_DAY','2026-12-24','UPSERT','1|2026-12-24|FREE|MANUAL|',null), '{"operation_id":"no-uuid"}'::jsonb))$q$,
  '22023', 'A: lote con una operación mal formada falla entero');
select test.ok(not exists (select 1 from jsonb_to_recordset(public.sync_pull(0, 1000)->'changes') as x(key text) where key = '2026-12-24'), 'A: nada del lote fallido se aplicó');
begin;
select test.ok(test.push1('d0000000-0000-4000-8000-000000000002', 'SCHEDULED_DAY', '2026-12-25', 'UPSERT', '1|2026-12-25|FREE|MANUAL|', null) ->> 'status' = 'APPLIED', 'A: aplicada dentro de una transacción');
rollback;
select test.ok(not exists (select 1 from jsonb_to_recordset(public.sync_pull(0, 1000)->'changes') as x(key text) where key = '2026-12-25'), 'A: transacción interrumpida = nada aplicado');
select test.ok(test.push1('d0000000-0000-4000-8000-000000000002', 'SCHEDULED_DAY', '2026-12-25', 'UPSERT', '1|2026-12-25|FREE|MANUAL|', null) ->> 'status' = 'APPLIED', 'A: el reintento de la misma operación se aplica una vez');

-- ---------- Account B: isolation ----------
select set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-00000000000b","role":"authenticated","exp":9999999999}', false);
select test.ok(jsonb_array_length(public.sync_pull(0, 1000)->'changes') = 0, 'B: no ve ningún registro de A');
select test.ok(test.push1('a0000000-0000-4000-8000-000000000001', 'SCHEDULED_DAY', '2026-12-02', 'UPSERT', '1|2026-12-02|FREE|MANUAL|', null)
  = '{"operation_id":"a0000000-0000-4000-8000-000000000001","status":"APPLIED","version":1,"seq":1}'::jsonb, 'B: mismo id de operación y clave que A = espacio propio, sin ver el resultado de A');
select test.ok((public.sync_pull(0, 1000)->'changes'->0->>'payload') = '1|2026-12-02|FREE|MANUAL|' and jsonb_array_length(public.sync_pull(0, 1000)->'changes') = 1, 'B: solo sus datos y su cursor');
select test.fails('select * from turno.records', '42501', 'B: lectura directa denegada');
reset role;
select test.ok((select count(*) from turno.records where user_id = '00000000-0000-4000-8000-00000000000a' and record_key = '2026-12-02' and version = 3 and deleted) = 1, 'B no modificó el registro de A');

-- ---------- Account deletion ----------
delete from auth.users where id = '00000000-0000-4000-8000-00000000000b';
select test.ok(not exists (select 1 from turno.records where user_id = '00000000-0000-4000-8000-00000000000b')
  and not exists (select 1 from turno.applied_mutations where user_id = '00000000-0000-4000-8000-00000000000b'), 'borrar la cuenta B elimina todos sus datos');
select test.ok(exists (select 1 from turno.records where user_id = '00000000-0000-4000-8000-00000000000a'), 'los datos de A siguen intactos');

\o
select 'RESULTADO: ' || count(*) filter (where ok) || ' superadas, ' || count(*) filter (where not ok) || ' fallidas' from test.results;
