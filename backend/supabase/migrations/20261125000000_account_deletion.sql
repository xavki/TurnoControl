-- Account deletion requested by the account owner (phase 5F.1D). Additive: no existing table or row is changed.
--
-- public.delete_my_account(confirmation):
--   * owner = turno.current_account() (verified JWT claims only; the client never sends an account id);
--   * explicit confirmation phrase;
--   * RECENT authentication: the newest `amr` timestamp in the token (the original sign-in time, which GoTrue keeps
--     across refreshes) must be at most 10 minutes old;
--   * the token's session (`session_id`) must still exist in auth.sessions: a signed-out or revoked session cannot
--     delete, even while its access token has not expired;
--   * one transaction; serialized with sync_push through the same per-account user_state row lock;
--   * idempotent: once the account is gone, repeating the call returns ALREADY_DELETED.
-- What is removed: the auth.users row, and through ON DELETE CASCADE every identity, session (and its refresh tokens),
-- MFA factor and all turno.* rows of the account (done by turno_ops.erase_account, see below). Also removed explicitly, because they have NO cascading foreign key
-- (verified against GoTrue v2.198.0): auth.flow_state rows, legacy auth.refresh_tokens rows, and the account's
-- auth.audit_log_entries (whose payload holds the e-mail address). A non-personal counter row is kept in
-- turno_ops.account_deletions (no id, no e-mail). Encrypted backups keep the data until they expire (BACKUP_PLAN.md).
--
-- turno.current_account() additionally requires the account to still exist, so a still-valid access token of a deleted
-- account gets a clean "account not found" (28000) instead of a foreign-key error that would expose table names.

-- Operational, never exposed schema (kept out of `turno`, whose four tables the security tests pin down).
create schema turno_ops authorization turno_owner;
revoke all on schema turno_ops from public;
create table turno_ops.account_deletions (
  id          bigserial primary key,
  deleted_at  timestamptz not null default now(),
  records     int not null,
  history     int not null,
  mutations   int not null,
  audit       int not null
);
alter table turno_ops.account_deletions owner to turno_owner;
revoke all on turno_ops.account_deletions from public;

-- GoTrue enables row-level security WITHOUT policies on its auth.* tables (observed with GoTrue v2.198.0), so neither
-- turno_owner nor any API role can see their rows. Giving turno_owner BYPASSRLS would also disable the FORCED RLS of the
-- turno tables (their second line of defence), so auth access goes through a separate NOLOGIN role that bypasses RLS
-- but holds ONLY column-level privileges on the few auth tables it needs, and owns three fixed-purpose helpers that only
-- turno_owner may execute.
create role turno_auth_ops nologin bypassrls;
grant usage on schema auth to turno_auth_ops;
grant usage on schema turno_ops to turno_auth_ops;
grant select (id) on auth.users to turno_auth_ops;
grant delete on auth.users to turno_auth_ops;
grant select (id, user_id, not_after) on auth.sessions to turno_auth_ops;
grant select (id, payload) on auth.audit_log_entries to turno_auth_ops;
grant delete on auth.audit_log_entries to turno_auth_ops;
grant select (user_id) on auth.flow_state to turno_auth_ops;
grant delete on auth.flow_state to turno_auth_ops;
grant select (user_id) on auth.refresh_tokens to turno_auth_ops;
grant delete on auth.refresh_tokens to turno_auth_ops;
grant insert on turno_ops.account_deletions to turno_auth_ops;
grant usage on sequence turno_ops.account_deletions_id_seq to turno_auth_ops;

create function turno_ops.account_exists(p_account uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from auth.users u where u.id = p_account)
$$;

create function turno_ops.session_valid(p_account uuid, p_session uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from auth.sessions s
                 where s.id = p_session and s.user_id = p_account and (s.not_after is null or s.not_after > now()))
$$;

-- Removes the identity (cascading to identities, sessions, refresh tokens, MFA and every turno.* row) plus the rows
-- that have no cascading key; records a non-personal counter. Returns the number of audit entries removed.
create function turno_ops.erase_account(p_account uuid, p_records int, p_history int, p_mutations int) returns int
language plpgsql volatile security definer set search_path = '' as $$
declare n_audit int;
begin
  delete from auth.audit_log_entries a where a.payload->>'actor_id' = p_account::text;
  get diagnostics n_audit = row_count;
  delete from auth.flow_state f where f.user_id = p_account;
  delete from auth.refresh_tokens r where r.user_id = p_account::text;
  delete from auth.users u where u.id = p_account;
  insert into turno_ops.account_deletions(records, history, mutations, audit) values (p_records, p_history, p_mutations, n_audit);
  return n_audit;
end $$;

alter function turno_ops.account_exists(uuid) owner to turno_auth_ops;
alter function turno_ops.session_valid(uuid, uuid) owner to turno_auth_ops;
alter function turno_ops.erase_account(uuid, int, int, int) owner to turno_auth_ops;
revoke all on function turno_ops.account_exists(uuid), turno_ops.session_valid(uuid, uuid), turno_ops.erase_account(uuid, int, int, int)
  from public, anon, authenticated, service_role;
grant execute on function turno_ops.account_exists(uuid), turno_ops.session_valid(uuid, uuid), turno_ops.erase_account(uuid, int, int, int)
  to turno_owner;

create or replace function turno.current_account() returns uuid
language plpgsql stable security invoker set search_path = '' as $$
declare
  claims jsonb := nullif(current_setting('request.jwt.claims', true), '')::jsonb;
  subject uuid;
begin
  if claims is null or claims->>'role' is distinct from 'authenticated' then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  if (claims->>'exp') is null or (claims->>'exp')::numeric <= extract(epoch from now()) then
    raise exception 'session expired' using errcode = '28000';
  end if;
  begin
    subject := (claims->>'sub')::uuid;
  exception when others then
    raise exception 'invalid subject' using errcode = '28000';
  end;
  if subject is null or subject is distinct from auth.uid() then
    raise exception 'invalid subject' using errcode = '28000';
  end if;
  if not turno_ops.account_exists(subject) then
    raise exception 'account not found' using errcode = '28000';
  end if;
  return subject;
end $$;

create function public.delete_my_account(p_confirmation text) returns jsonb
language plpgsql volatile security definer set search_path = '' as $$
declare
  claims jsonb := nullif(current_setting('request.jwt.claims', true), '')::jsonb;
  owner uuid;
  last_auth numeric;
  sid uuid;
  n_records int; n_history int; n_mutations int; n_audit int;
begin
  if p_confirmation is distinct from 'ELIMINAR MI CUENTA' then
    raise exception 'confirmation required' using errcode = '22023';
  end if;
  -- Same checks as current_account(), except that a missing account means the deletion already happened.
  if claims is null or claims->>'role' is distinct from 'authenticated'
     or (claims->>'exp') is null or (claims->>'exp')::numeric <= extract(epoch from now()) then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  begin
    owner := (claims->>'sub')::uuid;
  exception when others then
    raise exception 'invalid subject' using errcode = '28000';
  end;
  if owner is null or owner is distinct from auth.uid() then
    raise exception 'invalid subject' using errcode = '28000';
  end if;
  select max((e->>'timestamp')::numeric) into last_auth
    from jsonb_array_elements(case when jsonb_typeof(claims->'amr') = 'array' then claims->'amr' else '[]'::jsonb end) e;
  if last_auth is null or last_auth < extract(epoch from now()) - 600 then
    raise exception 'recent authentication required' using errcode = '28000';
  end if;
  if not turno_ops.account_exists(owner) then
    return jsonb_build_object('status', 'ALREADY_DELETED');
  end if;
  begin
    sid := (claims->>'session_id')::uuid;
  exception when others then
    sid := null;
  end;
  if sid is null or not turno_ops.session_valid(owner, sid) then
    raise exception 'session not valid' using errcode = '28000';
  end if;

  -- Serialize with sync_push: lock the account's user_state row if it exists (never create it here: inserting would take
  -- a key-share lock on auth.users and could deadlock with a concurrent first push).
  perform 1 from turno.user_state where user_id = owner for update;

  select count(*) into n_records   from turno.records          where user_id = owner;
  select count(*) into n_history   from turno.record_history   where user_id = owner;
  select count(*) into n_mutations from turno.applied_mutations where user_id = owner;
  n_audit := turno_ops.erase_account(owner, n_records, n_history, n_mutations);   -- cascades to auth.* and turno.*
  return jsonb_build_object('status', 'DELETED', 'records', n_records, 'history', n_history, 'mutations', n_mutations);
end $$;

alter function public.delete_my_account(text) owner to turno_owner;
revoke all on function public.delete_my_account(text) from public, anon, service_role;
grant execute on function public.delete_my_account(text) to authenticated;
