-- Retention (phase 5F.1D). Additive: creates an operator-only purge function and a run log; deletes nothing by itself.
--
-- turno_ops.purge_retention(days, apply) — run ONLY by an operator (scripts/retention.sh, as supabase_admin); no API role
-- may execute it. Dry run by default; exact counts; one transaction; minimum 30 days.
-- What it may delete (rows older than `days`):
--   * turno.record_history      — superseded versions, used only for manual recovery, never by synchronization;
--   * turno.applied_mutations   — idempotency memory, EXCEPT the row whose operation_id is a record's CURRENT version
--                                 (so retrying the latest operation of a record keeps answering APPLIED forever);
--   * auth.audit_log_entries    — security audit, holds e-mail addresses and IPs.
-- What it can never delete: turno.records (current state, tombstones included) and turno.user_state. Those two make
-- any offline period safe: sync_pull from an old cursor returns the CURRENT state of every key changed since then,
-- tombstones included, and a stale write is answered by the compare-and-swap on base_version (CONFLICT, never a
-- silent overwrite or a resurrection). A replay of an operation whose idempotency row was purged therefore yields
-- CONFLICT carrying the server's version; the Android client adopts it silently when the content is identical.
-- The run log keeps only counts (no account ids, no payloads).

create table turno_ops.retention_runs (
  id          bigserial primary key,
  run_at      timestamptz not null default now(),
  days        int not null,
  history     int not null,
  mutations   int not null,
  audit       int not null,
  accounts    int not null
);
revoke all on turno_ops.retention_runs from public;

create function turno_ops.purge_retention(p_days int, p_apply boolean default false) returns jsonb
language plpgsql volatile security invoker set search_path = '' as $$
declare
  cutoff timestamptz;
  n_history int; n_mutations int; n_audit int; n_accounts int; n_kept int;
  d_history int; d_mutations int; d_audit int;
begin
  if p_days is null or p_days < 30 then
    raise exception 'retention must be at least 30 days' using errcode = '22023';
  end if;
  cutoff := now() - make_interval(days => p_days);
  -- Counts (dry run and apply use exactly the same predicates).
  select count(*) into n_history from turno.record_history h where h.replaced_at < cutoff;
  select count(*) into n_mutations from turno.applied_mutations a
    where a.applied_at < cutoff
      and not exists (select 1 from turno.records r where r.user_id = a.user_id and r.operation_id = a.operation_id);
  select count(*) into n_kept from turno.applied_mutations a
    where a.applied_at < cutoff
      and exists (select 1 from turno.records r where r.user_id = a.user_id and r.operation_id = a.operation_id);
  select count(*) into n_audit from auth.audit_log_entries e where e.created_at < cutoff;
  select count(*) into n_accounts from (
    select h.user_id from turno.record_history h where h.replaced_at < cutoff
    union
    select a.user_id from turno.applied_mutations a where a.applied_at < cutoff
      and not exists (select 1 from turno.records r where r.user_id = a.user_id and r.operation_id = a.operation_id)) x;
  if not p_apply then
    return jsonb_build_object('mode', 'DRY_RUN', 'cutoff', cutoff, 'history', n_history, 'mutations', n_mutations,
      'mutations_kept_current', n_kept, 'audit', n_audit, 'accounts', n_accounts);
  end if;

  delete from turno.record_history h where h.replaced_at < cutoff;
  get diagnostics d_history = row_count;
  delete from turno.applied_mutations a
    where a.applied_at < cutoff
      and not exists (select 1 from turno.records r where r.user_id = a.user_id and r.operation_id = a.operation_id);
  get diagnostics d_mutations = row_count;
  delete from auth.audit_log_entries e where e.created_at < cutoff;
  get diagnostics d_audit = row_count;
  -- Same transaction: the counts cannot drift; if they did, nothing is committed.
  if d_history <> n_history or d_mutations <> n_mutations or d_audit <> n_audit then
    raise exception 'retention counts changed during the run; nothing was deleted' using errcode = '40001';
  end if;
  insert into turno_ops.retention_runs(days, history, mutations, audit, accounts)
    values (p_days, d_history, d_mutations, d_audit, n_accounts);
  return jsonb_build_object('mode', 'APPLIED', 'cutoff', cutoff, 'history', d_history, 'mutations', d_mutations,
    'mutations_kept_current', n_kept, 'audit', d_audit, 'accounts', n_accounts);
end $$;

revoke all on function turno_ops.purge_retention(int, boolean) from public, anon, authenticated, service_role, turno_owner;
