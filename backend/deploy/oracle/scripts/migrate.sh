#!/usr/bin/env bash
# Applies the TurnoControl SQL to the TurnoControl database. OPT-IN: without --apply it only reports what is pending.
#   scripts/migrate.sh                 # dry run: lists pending files, changes nothing
#   scripts/migrate.sh --apply         # applies pending files (requires a fresh verified backup, see below)
#   scripts/migrate.sh --apply --first-install   # empty database only (no `turno` schema yet): no backup required
#
# Order: db/hardening/*.sql, then backend/supabase/migrations/*.sql, by file name. Each file runs in ONE transaction
# and is recorded (name + sha256) in turno_deploy.applied_files. A recorded file whose content changed stops the run:
# migrations are append-only, never edited or re-run. Nothing here drops or recreates a schema.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
require_cmd docker sha256sum
apply=0; first=0
for a in "$@"; do case "$a" in --apply) apply=1;; --first-install) first=1;; *) die "opción desconocida: $a";; esac; done

assert_turno_db
# GoTrue must have finished its own migrations: auth.uid() must read request.jwt.claims (what PostgREST sets).
db_psql -At -c "select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'auth' and p.proname = 'uid' and pg_get_functiondef(p.oid) like '%request.jwt.claims%'" \
  | grep -qx 1 || die "auth.uid() aún no es la versión de GoTrue; espera a que turnocontrol-auth esté healthy"

has_turno=$(db_psql -At -c "select count(*) from pg_namespace where nspname = 'turno'")
if [ "$apply" = 1 ] && [ "$first" = 1 ] && [ "$has_turno" != 0 ]; then die "--first-install solo vale con la base vacía"; fi
if [ "$apply" = 1 ] && [ "$first" = 0 ]; then
  load_env_file "$TURNO_BACKUP_ENV_FILE"; require_vars TURNO_BACKUP_DIR
  newest=$(find "$TURNO_BACKUP_DIR" -maxdepth 1 -type f -name 'turnocontrol-db-*.dump.age' -mmin -120 -printf '%f\n' 2>/dev/null | sort | tail -1)
  [ -n "$newest" ] || die "no hay una copia de menos de 2 horas; ejecuta scripts/backup.sh y scripts/verify-backup.sh antes"
  "$DEPLOY_DIR/scripts/verify-backup.sh" "$TURNO_BACKUP_DIR/$newest" >/dev/null || die "la copia más reciente no supera la verificación"
  log "copia previa verificada: $newest"
fi

if [ "$apply" = 1 ]; then
  db_psql <<'SQL'
create schema if not exists turno_deploy;
revoke all on schema turno_deploy from public;
create table if not exists turno_deploy.applied_files (
  file_name text primary key, sha256 text not null, applied_at timestamptz not null default now());
SQL
fi

pending=0
for f in "$DEPLOY_DIR"/db/hardening/*.sql "$BACKEND_DIR"/supabase/migrations/*.sql; do
  name=$(basename "$f"); sum=$(sha256sum "$f" | cut -d' ' -f1)
  recorded=$(db_psql -At -c "select sha256 from turno_deploy.applied_files where file_name = '$name'" 2>/dev/null || true)
  if [ -n "$recorded" ]; then
    [ "$recorded" = "$sum" ] || die "$name ya se aplicó con otro contenido; las migraciones no se editan"
    continue
  fi
  pending=$((pending + 1))
  if [ "$apply" = 0 ]; then echo "PENDIENTE  $name"; continue; fi
  log "aplicando $name"
  { cat "$f"; printf "\ninsert into turno_deploy.applied_files(file_name, sha256) values ('%s', '%s');\n" "$name" "$sum"; } \
    | db_psql --single-transaction -f -
done
[ "$apply" = 0 ] && echo "Simulación: $pending pendientes. Nada aplicado (usa --apply)." || log "migración terminada ($pending aplicados)"
[ "$apply" = 1 ] && log "siguiente paso: tests/sql/verify_deployment.sql" || true
