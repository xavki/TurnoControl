-- Read-only verification of a deployed TurnoControl database (run as supabase_admin after scripts/migrate.sh --apply,
-- and by scripts/restore-drill.sh). Output: one `check|t` or `check|f` line per property. Changes nothing.
\pset footer off
select 'schema turno existe' as check_name, exists (select 1 from pg_namespace where nspname = 'turno') as ok
union all select 'cuatro tablas en turno', (select count(*) = 4 from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'turno' and c.relkind = 'r')
union all select 'RLS activada y forzada en turno', (select bool_and(relrowsecurity and relforcerowsecurity) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'turno' and c.relkind = 'r')
union all select 'RPC propiedad de turno_owner', (select bool_and(pg_get_userbyid(proowner) = 'turno_owner') from pg_proc where oid in ('public.sync_push(uuid,jsonb)'::regprocedure, 'public.sync_pull(bigint,int)'::regprocedure))
union all select 'RPC SECURITY DEFINER con search_path vacío', (select bool_and(prosecdef and (proconfig @> array['search_path=""'] or proconfig @> array['search_path='])) from pg_proc where oid in ('public.sync_push(uuid,jsonb)'::regprocedure, 'public.sync_pull(bigint,int)'::regprocedure))
union all select 'authenticated puede ejecutar las RPC', has_function_privilege('authenticated', 'public.sync_push(uuid,jsonb)', 'execute') and has_function_privilege('authenticated', 'public.sync_pull(bigint,int)', 'execute')
union all select 'anon NO puede ejecutar las RPC', not has_function_privilege('anon', 'public.sync_push(uuid,jsonb)', 'execute') and not has_function_privilege('anon', 'public.sync_pull(bigint,int)', 'execute')
union all select 'service_role NO puede ejecutar las RPC', not has_function_privilege('service_role', 'public.sync_push(uuid,jsonb)', 'execute') and not has_function_privilege('service_role', 'public.sync_pull(bigint,int)', 'execute')
union all select 'API sin acceso a tablas de turno', not exists (select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace, unnest(array['anon','authenticated','service_role']) r
  where n.nspname = 'turno' and c.relkind = 'r' and has_table_privilege(r, c.oid, 'select,insert,update,delete'))
union all select 'API sin USAGE en el esquema turno', not has_schema_privilege('anon', 'turno', 'usage') and not has_schema_privilege('authenticated', 'turno', 'usage')
union all select 'authenticator no asume service_role ni supabase_admin', not pg_has_role('authenticator', 'service_role', 'member') and not pg_has_role('authenticator', 'supabase_admin', 'member')
union all select 'authenticator no asume turno_owner', not pg_has_role('authenticator', 'turno_owner', 'member')
union all select 'solo dos funciones SECURITY DEFINER propias', (select count(*) = 2 from pg_proc p join pg_namespace n on n.oid = p.pronamespace where p.prosecdef and n.nspname in ('public','turno'))
union all select 'auth.uid() lee request.jwt.claims (GoTrue migrado)', exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'auth' and p.proname = 'uid' and pg_get_functiondef(p.oid) like '%request.jwt.claims%')
union all select 'claves foráneas a auth.users con borrado en cascada', (select count(*) = 4 from pg_constraint where contype = 'f' and confrelid = 'auth.users'::regclass and confdeltype = 'c' and connamespace = 'turno'::regnamespace);
