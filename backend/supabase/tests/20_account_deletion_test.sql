-- Account-deletion tests (phase 5F.1D). Synthetic accounts only. Run by run-local-tests.sh after 10_sync_security_test.sql
-- (reuses its test.ok / test.fails helpers and results table).
\set ON_ERROR_STOP 1
\set QUIET 1
\o /dev/null
-- C will be deleted; D is a bystander that must stay untouched.
insert into auth.users (id, email) values ('00000000-0000-4000-8000-0000000000c1', 'carla@example.invalid'), ('00000000-0000-4000-8000-0000000000d1', 'dani@example.invalid');
insert into auth.sessions (id, user_id, not_after) values
  ('5e550000-0000-4000-8000-0000000000c1', '00000000-0000-4000-8000-0000000000c1', null),
  ('5e550000-0000-4000-8000-0000000000c2', '00000000-0000-4000-8000-0000000000c1', now() - interval '1 minute'),
  ('5e550000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-0000000000d1', null);
insert into auth.refresh_tokens (user_id, session_id) values ('00000000-0000-4000-8000-0000000000c1', '5e550000-0000-4000-8000-0000000000c1'),
  ('00000000-0000-4000-8000-0000000000c1', null), ('00000000-0000-4000-8000-0000000000d1', '5e550000-0000-4000-8000-0000000000d1');
insert into auth.flow_state (id, user_id) values ('f1000000-0000-4000-8000-0000000000c1', '00000000-0000-4000-8000-0000000000c1'), ('f1000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-0000000000d1');
insert into auth.audit_log_entries (id, payload) values
  ('a0d10000-0000-4000-8000-0000000000c1', '{"actor_id":"00000000-0000-4000-8000-0000000000c1","actor_username":"carla@example.invalid","action":"login"}'),
  ('a0d10000-0000-4000-8000-0000000000c2', '{"actor_id":"00000000-0000-4000-8000-0000000000c1","actor_username":"carla@example.invalid","action":"token_refreshed"}'),
  ('a0d10000-0000-4000-8000-0000000000d1', '{"actor_id":"00000000-0000-4000-8000-0000000000d1","actor_username":"dani@example.invalid","action":"login"}');

-- Claims as GoTrue issues them: role, exp, sub, session_id and amr (original sign-in time).
create function test.claims(sub text, session text, auth_age_seconds int, exp_offset int default 600) returns void language sql as $$
  select set_config('request.jwt.claims', jsonb_build_object('role', 'authenticated', 'sub', sub, 'session_id', session,
    'exp', extract(epoch from now())::int + exp_offset,
    'amr', jsonb_build_array(jsonb_build_object('method', 'password', 'timestamp', extract(epoch from now())::int - auth_age_seconds)))::text, false) is null
$$;
grant execute on function test.claims(text, text, int, int) to public;

-- Data for both accounts through the real RPC.
set role authenticated;
select test.claims('00000000-0000-4000-8000-0000000000c1', '5e550000-0000-4000-8000-0000000000c1', 5);
select public.sync_push('11111111-1111-4111-8111-1111111111c1', jsonb_build_array(test.m('c1000000-0000-4000-8000-000000000001', 'SCHEDULED_DAY', '2027-07-01', 'UPSERT', '1|2027-07-01|FREE|MANUAL|', null)));
select public.sync_push('11111111-1111-4111-8111-1111111111c1', jsonb_build_array(test.m('c1000000-0000-4000-8000-000000000002', 'SCHEDULED_DAY', '2027-07-01', 'UPSERT', '1|2027-07-01|SICK_LEAVE|MANUAL|', 1)));
select test.claims('00000000-0000-4000-8000-0000000000d1', '5e550000-0000-4000-8000-0000000000d1', 5);
select public.sync_push('11111111-1111-4111-8111-1111111111d1', jsonb_build_array(test.m('d1000000-0000-4000-8000-000000000001', 'SCHEDULED_DAY', '2027-07-01', 'UPSERT', '1|2027-07-01|VACATION|MANUAL|', null)));
reset role;

-- ---------- Privileges
select test.ok(has_function_privilege('authenticated', 'public.delete_my_account(text)', 'execute'), 'borrado: authenticated puede ejecutar delete_my_account');
select test.ok(not has_function_privilege('anon', 'public.delete_my_account(text)', 'execute') and not has_function_privilege('service_role', 'public.delete_my_account(text)', 'execute'), 'borrado: anon y service_role no pueden ejecutarla');
select test.ok((select p.proacl is not null and not exists (select 1 from aclexplode(p.proacl) a where a.grantee in (0, 'anon'::regrole::oid, 'service_role'::regrole::oid))
  from pg_proc p where p.oid = 'public.delete_my_account(text)'::regprocedure), 'borrado: ACL sin PUBLIC, anon ni service_role');
select test.ok(not exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace, unnest(array['anon','authenticated','service_role','authenticator']) r
  where n.nspname = 'turno_ops' and has_function_privilege(r, p.oid, 'execute')), 'borrado: los auxiliares de turno_ops no son ejecutables por la API');
select test.ok((select not rolcanlogin and not rolsuper from pg_roles where rolname = 'turno_auth_ops') and not pg_has_role('authenticator', 'turno_auth_ops', 'member'), 'borrado: turno_auth_ops es NOLOGIN, sin superusuario e inaccesible desde la API');
select test.ok(not has_schema_privilege('authenticated', 'turno_ops', 'usage') and not has_schema_privilege('anon', 'turno_ops', 'usage'), 'borrado: turno_ops no es accesible para la API');
select test.ok((select pg_get_userbyid(proowner) = 'turno_owner' and prosecdef and proconfig @> array['search_path=""'] from pg_proc where oid = 'public.delete_my_account(text)'::regprocedure), 'borrado: SECURITY DEFINER de turno_owner con search_path vacío');

-- ---------- Rejections (C keeps everything after each one)
set role anon;
select test.fails('select public.delete_my_account(''ELIMINAR MI CUENTA'')', '42501', 'borrado: anónimo denegado');
reset role;
set role authenticated;
select set_config('request.jwt.claims', '', false);
select test.fails('select public.delete_my_account(''ELIMINAR MI CUENTA'')', '28000', 'borrado: sin sesión, rechazado');
select test.claims('00000000-0000-4000-8000-0000000000c1', '5e550000-0000-4000-8000-0000000000c1', 5);
select test.fails('select public.delete_my_account(''eliminar'')', '22023', 'borrado: frase de confirmación incorrecta');
select test.fails('select public.delete_my_account(null)', '22023', 'borrado: sin confirmación');
select test.claims('00000000-0000-4000-8000-0000000000c1', '5e550000-0000-4000-8000-0000000000c1', 3600);
select test.fails('select public.delete_my_account(''ELIMINAR MI CUENTA'')', '28000', 'borrado: autenticación de hace 1 h, rechazado (exige ≤ 10 min)');
select set_config('request.jwt.claims', '{"role":"authenticated","sub":"00000000-0000-4000-8000-0000000000c1","session_id":"5e550000-0000-4000-8000-0000000000c1","exp":9999999999}', false);
select test.fails('select public.delete_my_account(''ELIMINAR MI CUENTA'')', '28000', 'borrado: token sin amr, rechazado');
select test.claims('00000000-0000-4000-8000-0000000000c1', '5e550000-0000-4000-8000-0000000000c1', 5, -10);
select test.fails('select public.delete_my_account(''ELIMINAR MI CUENTA'')', '28000', 'borrado: token caducado, rechazado');
select test.claims('00000000-0000-4000-8000-0000000000c1', '5e550000-0000-4000-8000-0000000000c9', 5);
select test.fails('select public.delete_my_account(''ELIMINAR MI CUENTA'')', '28000', 'borrado: sesión inexistente (cerrada), rechazado');
select test.claims('00000000-0000-4000-8000-0000000000c1', '5e550000-0000-4000-8000-0000000000c2', 5);
select test.fails('select public.delete_my_account(''ELIMINAR MI CUENTA'')', '28000', 'borrado: sesión caducada (not_after), rechazado');
select test.claims('00000000-0000-4000-8000-0000000000c1', '5e550000-0000-4000-8000-0000000000d1', 5);
select test.fails('select public.delete_my_account(''ELIMINAR MI CUENTA'')', '28000', 'borrado: sesión de otra cuenta, rechazado');
select test.claims('00000000-0000-4000-8000-0000000000d1', '5e550000-0000-4000-8000-0000000000c1', 5);
select test.fails('select public.delete_my_account(''ELIMINAR MI CUENTA'')', '28000', 'borrado: sub de D con la sesión de C, rechazado');
select test.claims('not-a-uuid', '5e550000-0000-4000-8000-0000000000c1', 5);
select test.fails('select public.delete_my_account(''ELIMINAR MI CUENTA'')', '28000', 'borrado: sub que no es UUID, rechazado');
reset role;
select test.ok((select count(*) = 1 from auth.users where id = '00000000-0000-4000-8000-0000000000c1') and (select count(*) = 1 from turno.records where user_id = '00000000-0000-4000-8000-0000000000c1'),
  'borrado: tras los rechazos, C sigue intacta');

-- ---------- Valid deletion of C
create temp table deletion_result(out jsonb);
grant all on deletion_result to authenticated;
set role authenticated;
select test.claims('00000000-0000-4000-8000-0000000000c1', '5e550000-0000-4000-8000-0000000000c1', 5);
insert into deletion_result select public.delete_my_account('ELIMINAR MI CUENTA');
reset role;
select test.ok((select out->>'status' = 'DELETED' and (out->>'records')::int = 1 and (out->>'history')::int = 1 and (out->>'mutations')::int = 2 from deletion_result), 'borrado: DELETED con los recuentos correctos');
select test.ok(not exists (select 1 from auth.users where id = '00000000-0000-4000-8000-0000000000c1'), 'borrado: la identidad de C desaparece');
select test.ok(not exists (select 1 from turno.records where user_id = '00000000-0000-4000-8000-0000000000c1')
  and not exists (select 1 from turno.record_history where user_id = '00000000-0000-4000-8000-0000000000c1')
  and not exists (select 1 from turno.applied_mutations where user_id = '00000000-0000-4000-8000-0000000000c1')
  and not exists (select 1 from turno.user_state where user_id = '00000000-0000-4000-8000-0000000000c1'), 'borrado: registros, historial, mutaciones y estado de C desaparecen');
select test.ok(not exists (select 1 from auth.sessions where user_id = '00000000-0000-4000-8000-0000000000c1')
  and not exists (select 1 from auth.refresh_tokens where user_id = '00000000-0000-4000-8000-0000000000c1'), 'borrado: sesiones y refresh tokens de C desaparecen (también el que no tenía sesión)');
select test.ok(not exists (select 1 from auth.flow_state where user_id = '00000000-0000-4000-8000-0000000000c1'), 'borrado: flow_state de C desaparece (no tiene FK)');
select test.ok(not exists (select 1 from auth.audit_log_entries where payload::text like '%0000000000c1%' or payload::text like '%carla@%'), 'borrado: ninguna entrada de auditoría con el id o el correo de C');
select test.ok((select count(*) = 1 from turno.records where user_id = '00000000-0000-4000-8000-0000000000d1')
  and (select count(*) = 1 from auth.sessions where user_id = '00000000-0000-4000-8000-0000000000d1')
  and (select count(*) = 1 from auth.refresh_tokens where user_id = '00000000-0000-4000-8000-0000000000d1')
  and (select count(*) = 1 from auth.flow_state where user_id = '00000000-0000-4000-8000-0000000000d1')
  and (select count(*) = 1 from auth.audit_log_entries where payload->>'actor_id' = '00000000-0000-4000-8000-0000000000d1'), 'borrado: D no pierde nada');
select test.ok((select count(*) = 1 from turno_ops.account_deletions)
  and not exists (select 1 from information_schema.columns where table_schema = 'turno_ops' and table_name = 'account_deletions' and data_type in ('uuid', 'text', 'character varying')),
  'borrado: queda solo un contador sin identificadores');

-- ---------- After deletion: idempotent, and no resurrection with the still-valid token
set role authenticated;
select test.claims('00000000-0000-4000-8000-0000000000c1', '5e550000-0000-4000-8000-0000000000c1', 5);
select test.ok(public.delete_my_account('ELIMINAR MI CUENTA')->>'status' = 'ALREADY_DELETED', 'borrado: repetir la petición devuelve ALREADY_DELETED');
select test.fails('select public.sync_push(''11111111-1111-4111-8111-1111111111c1'', jsonb_build_array(test.m(''c1000000-0000-4000-8000-000000000003'', ''SCHEDULED_DAY'', ''2027-07-02'', ''UPSERT'', ''1|2027-07-02|FREE|MANUAL|'', null)))', '28000', 'borrado: un push con el token aún vigente se rechaza (account not found)');
select test.fails('select public.sync_pull(0, 10)', '28000', 'borrado: un pull con el token aún vigente se rechaza');
reset role;
select test.ok(not exists (select 1 from turno.user_state where user_id = '00000000-0000-4000-8000-0000000000c1') and not exists (select 1 from turno.records where user_id = '00000000-0000-4000-8000-0000000000c1'), 'borrado: la cuenta no resucita');
select test.ok((select count(*) = 1 from turno_ops.account_deletions), 'borrado: la repetición no añade otro registro');
\o
select 'RESULTADO BORRADO: ' || count(*) filter (where ok and name like 'borrado:%') || ' superadas, ' || count(*) filter (where not ok and name like 'borrado:%') || ' fallidas' from test.results;
