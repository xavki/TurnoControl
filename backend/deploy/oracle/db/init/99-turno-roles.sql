-- Runs ONCE, on the first start with an empty data directory (Supabase image, init-scripts phase, via psql).
-- Gives the two login roles the stack uses their OWN passwords (the official example reuses one password for all).
-- Changing the values in .env later has NO effect: rotate with ALTER ROLE (SECURITY.md, "Rotación").
-- Roles the stack does not use (pgbouncer, supabase_storage_admin, supabase_functions_admin, ...) keep no password,
-- so they cannot authenticate over the network (scram-sha-256 with a null password always fails).
\set authenticator_pass `printf '%s' "$TURNO_AUTHENTICATOR_PASSWORD"`
\set auth_admin_pass `printf '%s' "$TURNO_AUTH_ADMIN_PASSWORD"`
alter user authenticator with password :'authenticator_pass';
alter user supabase_auth_admin with password :'auth_admin_pass';
