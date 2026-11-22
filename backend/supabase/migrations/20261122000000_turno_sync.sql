-- TurnoControl 0.3.0 — synchronization backend (phase 5A). LOCAL TESTS ONLY so far; not deployed anywhere.
-- Mirrors the client protocol in app/src/main/java/com/turnocontrol/domain/sync (canonical text payloads).
--
-- Security model (least privilege, two layers):
--  1. `authenticated` has NO privilege on any table: it may only EXECUTE public.sync_push / public.sync_pull.
--  2. Those functions are SECURITY DEFINER, owned by the NOLOGIN role turno_owner, with an empty search_path, and they
--     take the owner exclusively from the verified session (turno.current_account()); RLS is ENABLED and FORCED on every
--     table, so even the owning role only ever sees rows of auth.uid().
-- `anon` has no access at all. Physical deletes only happen through ON DELETE CASCADE when an auth user is deleted.

create role turno_owner nologin;
create schema turno authorization turno_owner;
revoke all on schema turno from public;

-- Verified identity: Supabase/PostgREST have already checked the JWT signature; this re-checks role, subject and expiry.
create function turno.current_account() returns uuid
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
  return subject;
end $$;

create table turno.user_state (
  user_id   uuid primary key references auth.users(id) on delete cascade,
  last_seq  bigint not null default 0 check (last_seq >= 0)
);

create table turno.records (
  user_id           uuid   not null references auth.users(id) on delete cascade,
  entity            text   not null check (entity in ('SCHEDULED_DAY','PAY_RULE','PAYSLIP','PROFILE','CONTRACT')),
  record_key        text   not null check (length(record_key) between 1 and 100),
  version           bigint not null check (version >= 1),
  seq               bigint not null check (seq >= 1),
  deleted           boolean not null,
  data_format       int    not null check (data_format = 1),
  payload           text,
  operation_id      uuid   not null,
  updated_by_device uuid   not null,
  updated_at        timestamptz not null default now(),   -- informative only; never used for ordering
  primary key (user_id, entity, record_key),
  check ((deleted and payload is null) or (not deleted and payload is not null)),
  check (payload is null or octet_length(payload) <= 16384)
);
create unique index records_user_seq on turno.records (user_id, seq);

-- Every replaced version, kept for recovery (proposed retention: 90 days; purge job not configured locally).
create table turno.record_history (
  user_id uuid not null references auth.users(id) on delete cascade,
  entity text not null, record_key text not null, version bigint not null,
  deleted boolean not null, data_format int not null, payload text, operation_id uuid not null,
  replaced_at timestamptz not null default now(),
  primary key (user_id, entity, record_key, version)
);

create table turno.applied_mutations (
  user_id      uuid not null references auth.users(id) on delete cascade,
  operation_id uuid not null,
  result       jsonb not null,
  applied_at   timestamptz not null default now(),
  primary key (user_id, operation_id)
);

alter table turno.user_state        owner to turno_owner;
alter table turno.records           owner to turno_owner;
alter table turno.record_history    owner to turno_owner;
alter table turno.applied_mutations owner to turno_owner;
alter function turno.current_account() owner to turno_owner;

do $$
declare t text;
begin
  foreach t in array array['user_state','records','record_history','applied_mutations'] loop
    execute format('alter table turno.%I enable row level security', t);
    execute format('alter table turno.%I force row level security', t);
    execute format('create policy own_rows on turno.%I for all to turno_owner using (user_id = auth.uid()) with check (user_id = auth.uid())', t);
  end loop;
end $$;

-- ---------------------------------------------------------------------------------------------------------------
-- Payload validation: structure and value ranges of the canonical formats (defence in depth; the Kotlin codecs remain
-- the full authority, cross-record rules are checked on the client). Returns null when acceptable.
-- ---------------------------------------------------------------------------------------------------------------
create function turno.valid_date(value text) returns boolean
language plpgsql immutable security invoker set search_path = '' as $$
begin
  if value !~ '^\d{4}-\d{2}-\d{2}$' then return false; end if;
  perform value::date;
  return to_char(value::date, 'YYYY-MM-DD') = value;
exception when others then return false;
end $$;

create function turno.day_error(record_key text, payload text) returns text
language plpgsql immutable security invoker set search_path = '' as $$
declare
  parts text[] := string_to_array(payload, '|');
  item text; m text[]; s int; e int; previous_end int := -1; total int := 0; n int := 0;
begin
  if array_length(parts, 1) is distinct from 5 or parts[1] <> '1' then return 'formato de día no admitido'; end if;
  if not turno.valid_date(record_key) or parts[2] <> record_key then return 'la clave no coincide con la fecha'; end if;
  if parts[3] not in ('WORK','FREE','VACATION','SICK_LEAVE') then return 'tipo de día no admitido'; end if;
  if parts[4] not in ('MANUAL','OCR_REVIEWED') then return 'origen no admitido'; end if;
  if parts[3] = 'SICK_LEAVE' and (parts[4] <> 'MANUAL' or parts[5] <> '') then return 'una baja solo lleva fecha y estado'; end if;
  if parts[3] <> 'WORK' then return case when parts[5] = '' then null else 'solo un día de trabajo lleva intervalos' end; end if;
  if parts[5] = '' then return 'un día de trabajo necesita intervalos'; end if;
  foreach item in array string_to_array(parts[5], ',') loop
    n := n + 1;
    if n > 8 then return 'demasiados intervalos'; end if;
    m := regexp_match(item, '^(\d{2}):(\d{2})\+([01])-(\d{2}):(\d{2})\+([01]):([NE])$');
    if m is null or m[1]::int > 23 or m[2]::int > 59 or m[4]::int > 23 or m[5]::int > 59 then return 'intervalo no válido'; end if;
    s := m[3]::int * 1440 + m[1]::int * 60 + m[2]::int;
    e := m[6]::int * 1440 + m[4]::int * 60 + m[5]::int;
    if n = 1 and m[3] <> '0' then return 'el turno pertenece al día en que empieza'; end if;
    if e <= s or e - s > 1440 then return 'duración no válida'; end if;
    if s < previous_end then return 'intervalos solapados o desordenados'; end if;
    previous_end := e; total := total + (e - s);
  end loop;
  if total > 1440 then return 'más de 24 horas en un día'; end if;
  return null;
end $$;

create function turno.amount_ok(value text, decimals int) returns boolean
language sql immutable security invoker set search_path = '' as $$
  select value ~ ('^(0|[1-9][0-9]{0,11})(\.[0-9]{0,' || (decimals - 1) || '}[1-9])?$')
$$;

create function turno.payload_error(entity text, record_key text, payload text) returns text
language plpgsql immutable security invoker set search_path = '' as $$
declare parts text[] := string_to_array(payload, '|');
begin
  if payload ~ '[\x00-\x1F\x7F]' then return 'caracteres de control no admitidos'; end if;
  case entity
    when 'SCHEDULED_DAY' then return turno.day_error(record_key, payload);
    when 'PAY_RULE' then
      if array_length(parts, 1) is distinct from 6 or parts[1] <> '1' then return 'formato de tarifa no admitido'; end if;
      if parts[2] not in ('HOURLY_BASE','EXTRA_PAY','COMPLEMENTARY','NIGHT','SPECIAL_NIGHT','EXTRA_HOUR') then return 'tipo de tarifa no admitido'; end if;
      if record_key <> parts[2] || '@' || parts[3] or not turno.valid_date(parts[3]) then return 'la clave no coincide con la tarifa'; end if;
      if parts[4] <> '-' and (not turno.valid_date(parts[4]) or parts[4]::date < parts[3]::date) then return 'vigencia no válida'; end if;
      if not turno.amount_ok(parts[5], 4) then return 'importe no admitido'; end if;
      if parts[6] !~ '^[A-Z]{3}$' then return 'moneda no válida'; end if;
      return null;
    when 'PAYSLIP' then
      if array_length(parts, 1) is distinct from 4 or parts[1] <> '1' or parts[2] <> record_key or record_key !~ '^\d{4}-(0[1-9]|1[0-2])$' then return 'formato de nómina no admitido'; end if;
      if parts[3] = '-' and parts[4] = '-' then return 'la nómina necesita un importe'; end if;
      if (parts[3] <> '-' and not turno.amount_ok(parts[3], 2)) or (parts[4] <> '-' and not turno.amount_ok(parts[4], 2)) then return 'importe no admitido'; end if;
      return null;
    when 'PROFILE' then
      if record_key <> 'profile' or array_length(parts, 1) is distinct from 4 or parts[1] <> '1' then return 'formato de perfil no admitido'; end if;
      if parts[2] = '' or length(parts[2]) > 1000 or length(parts[3]) > 1000 then return 'perfil no válido'; end if;
      if parts[2] || parts[3] ~ '%(?!25|7C|0A|0D)' then return 'codificación no admitida'; end if;
      if parts[4] !~ '^[A-Za-z]+(/[A-Za-z0-9_+\-]+){0,2}$' then return 'zona horaria no válida'; end if;
      return null;
    when 'CONTRACT' then
      if record_key <> 'contract' or array_length(parts, 1) is distinct from 6 or parts[1] <> '1' then return 'formato de contrato no admitido'; end if;
      if parts[2] not in ('PARTIAL','FULL') or parts[4] not in ('WEEK','MONTH','YEAR') then return 'contrato no válido'; end if;
      if parts[3] !~ '^[1-9][0-9]{0,8}$' or not turno.valid_date(parts[5]) then return 'contrato no válido'; end if;
      if parts[6] <> '-' and (not turno.valid_date(parts[6]) or parts[6]::date < parts[5]::date) then return 'vigencia no válida'; end if;
      return null;
    else return 'entidad no admitida';
  end case;
end $$;

alter function turno.valid_date(text) owner to turno_owner;
alter function turno.day_error(text, text) owner to turno_owner;
alter function turno.amount_ok(text, int) owner to turno_owner;
alter function turno.payload_error(text, text, text) owner to turno_owner;

-- ---------------------------------------------------------------------------------------------------------------
-- RPC: push. One transaction per call; per-account serialization; compare-and-swap on base_version; idempotent by
-- operation_id. Structural errors abort the whole batch; content errors are REJECTED per operation (and remembered).
-- ---------------------------------------------------------------------------------------------------------------
create function public.sync_push(p_device_id uuid, p_mutations jsonb) returns jsonb
language plpgsql volatile security definer set search_path = '' as $$
declare
  owner uuid := turno.current_account();
  m jsonb; previous jsonb; result jsonb; results jsonb := '[]'::jsonb;
  op_id uuid; v_entity text; v_key text; v_op text; v_payload text; v_base bigint; v_format int;
  current_row turno.records%rowtype; found_row boolean; next_seq bigint; next_version bigint; problem text;
  allowed constant text[] := array['operation_id','entity','key','op','payload','base_version','data_format'];
begin
  if p_device_id is null then raise exception 'device id required' using errcode = '22023'; end if;
  if jsonb_typeof(p_mutations) is distinct from 'array' or jsonb_array_length(p_mutations) not between 1 and 100 then
    raise exception 'invalid batch' using errcode = '22023';
  end if;
  insert into turno.user_state(user_id) values (owner) on conflict (user_id) do nothing;
  perform 1 from turno.user_state where user_id = owner for update;          -- seq order = commit order
  for m in select value from jsonb_array_elements(p_mutations) loop
    -- Strict shape: unknown fields (e.g. a forged "user_id" or medical notes) abort the batch.
    if jsonb_typeof(m) <> 'object' or exists (select 1 from jsonb_object_keys(m) k where k <> all(allowed)) then
      raise exception 'unexpected field in mutation' using errcode = '22023';
    end if;
    begin
      op_id := (m->>'operation_id')::uuid;
      v_entity := m->>'entity'; v_key := m->>'key'; v_op := m->>'op';
      v_payload := case when jsonb_typeof(m->'payload') = 'string' then m->>'payload' when m->'payload' is null or jsonb_typeof(m->'payload') = 'null' then null else '!' end;
      v_base := case when m->'base_version' is null or jsonb_typeof(m->'base_version') = 'null' then null else (m->>'base_version')::bigint end;
      v_format := (m->>'data_format')::int;
    exception when others then
      raise exception 'malformed mutation' using errcode = '22023';
    end;
    if op_id is null or v_entity is null or v_key is null or v_op not in ('UPSERT','DELETE') or v_format is null or v_payload = '!' then
      raise exception 'malformed mutation' using errcode = '22023';
    end if;

    select r.result into previous from turno.applied_mutations r where r.user_id = owner and r.operation_id = op_id;
    if found then results := results || jsonb_build_array(previous); continue; end if;

    problem := case
      when v_format <> 1 then 'formato de datos no admitido'
      when v_entity not in ('SCHEDULED_DAY','PAY_RULE','PAYSLIP','PROFILE','CONTRACT') then 'entidad no admitida'
      when length(v_key) not between 1 and 100 then 'clave no válida'
      when v_op = 'DELETE' and v_payload is not null then 'un borrado no lleva contenido'
      when v_op = 'UPSERT' and v_payload is null then 'falta el contenido'
      when v_op = 'UPSERT' and octet_length(v_payload) > 16384 then 'contenido demasiado grande'
      when v_op = 'UPSERT' then turno.payload_error(v_entity, v_key, v_payload)
      else null end;

    select * into current_row from turno.records r where r.user_id = owner and r.entity = v_entity and r.record_key = v_key for update;
    found_row := found;
    if problem is not null then
      result := jsonb_build_object('operation_id', op_id, 'status', 'REJECTED', 'reason', problem);
    elsif (case when found_row then current_row.version else null end) is distinct from v_base then
      result := jsonb_build_object('operation_id', op_id, 'status', 'CONFLICT', 'remote', case when not found_row then null else
        jsonb_build_object('key', current_row.record_key, 'entity', current_row.entity, 'version', current_row.version, 'seq', current_row.seq,
          'deleted', current_row.deleted, 'data_format', current_row.data_format, 'payload', current_row.payload, 'operation_id', current_row.operation_id) end);
    else
      if found_row then
        insert into turno.record_history(user_id, entity, record_key, version, deleted, data_format, payload, operation_id)
        values (owner, current_row.entity, current_row.record_key, current_row.version, current_row.deleted, current_row.data_format, current_row.payload, current_row.operation_id);
      end if;
      update turno.user_state set last_seq = last_seq + 1 where user_id = owner returning last_seq into next_seq;
      next_version := coalesce(case when found_row then current_row.version end, 0) + 1;
      insert into turno.records(user_id, entity, record_key, version, seq, deleted, data_format, payload, operation_id, updated_by_device)
      values (owner, v_entity, v_key, next_version, next_seq, v_op = 'DELETE', v_format, case when v_op = 'DELETE' then null else v_payload end, op_id, p_device_id)
      on conflict (user_id, entity, record_key) do update set version = excluded.version, seq = excluded.seq, deleted = excluded.deleted,
        data_format = excluded.data_format, payload = excluded.payload, operation_id = excluded.operation_id,
        updated_by_device = excluded.updated_by_device, updated_at = now();
      result := jsonb_build_object('operation_id', op_id, 'status', 'APPLIED', 'version', next_version, 'seq', next_seq);
    end if;
    insert into turno.applied_mutations(user_id, operation_id, result) values (owner, op_id, result);
    results := results || jsonb_build_array(result);
  end loop;
  return results;
end $$;

-- RPC: pull. Account-scoped, ordered by the server sequence, paginated; the cursor is validated.
create function public.sync_pull(p_since bigint, p_limit int) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare
  owner uuid := turno.current_account();
  lim int;
  page jsonb; last_seq_in_page bigint; more boolean;
begin
  if p_since is null or p_since < 0 then raise exception 'invalid cursor' using errcode = '22023'; end if;
  lim := least(greatest(coalesce(p_limit, 500), 1), 1000);
  with rows as (
    select r.* from turno.records r where r.user_id = owner and r.seq > p_since order by r.seq limit lim + 1
  ), shown as (select * from rows order by seq limit lim)
  select coalesce(jsonb_agg(jsonb_build_object('key', record_key, 'entity', entity, 'version', version, 'seq', seq, 'deleted', deleted,
           'data_format', data_format, 'payload', payload, 'operation_id', operation_id) order by seq), '[]'::jsonb),
         max(seq), (select count(*) from rows) > lim
    into page, last_seq_in_page, more from shown;
  return jsonb_build_object('changes', page, 'next_cursor', coalesce(last_seq_in_page, p_since), 'has_more', more);
end $$;

alter function public.sync_push(uuid, jsonb) owner to turno_owner;
alter function public.sync_pull(bigint, int) owner to turno_owner;
grant usage on schema auth to turno_owner;
grant execute on function auth.uid() to turno_owner;
grant references on auth.users to turno_owner;

revoke all on function public.sync_push(uuid, jsonb), public.sync_pull(bigint, int) from public;
grant execute on function public.sync_push(uuid, jsonb), public.sync_pull(bigint, int) to authenticated;
revoke all on all functions in schema turno from public;
