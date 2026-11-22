-- LOCAL TESTS ONLY. Hosted Supabase already provides these objects (and verifies JWT signatures in its API gateway,
-- which this shim does NOT do). It reproduces how Supabase exposes the verified session to SQL: the roles anon /
-- authenticated / service_role and auth.uid() reading the request.jwt.claims setting.
create role anon nologin;
create role authenticated nologin;
create role service_role nologin bypassrls;
-- Supabase's API connects as `authenticator`, which can only switch to these request roles.
create role authenticator noinherit nologin;
grant anon, authenticated to authenticator;
create schema auth;
create table auth.users (id uuid primary key, email text);
create function auth.uid() returns uuid language sql stable as $$
  select nullif(coalesce(current_setting('request.jwt.claim.sub', true), (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')), '')::uuid
$$;
grant usage on schema auth to anon, authenticated, service_role;
grant execute on function auth.uid() to anon, authenticated, service_role;
