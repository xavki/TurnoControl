#!/usr/bin/env bash
# Operator-only retention run (phase 5F.1D). OPT-IN: without --apply it only reports what WOULD be deleted.
#   scripts/retention.sh                      # dry run, 90 days
#   scripts/retention.sh --days 120           # dry run, 120 days
#   scripts/retention.sh --apply [--days N]   # deletes; requires a fresh verified backup (like migrate.sh)
# Deletes only turno.record_history, non-current turno.applied_mutations and auth.audit_log_entries older than N days
# (minimum 30, enforced in SQL). It can never touch turno.records (current state and tombstones) — see the migration
# 20261126000000_retention.sql. Prints counts only, never ids or payloads. NOT scheduled anywhere: scheduling it is a
# separate, approved operation.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
require_cmd docker find
apply=0; days=90
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) apply=1;;
    --days) shift; days=${1:?falta el número de días};;
    *) die "opción desconocida: $1";;
  esac; shift
done
[[ "$days" =~ ^[0-9]+$ ]] || die "--days debe ser un número"
assert_turno_db
if [ "$apply" = 1 ]; then
  load_env_file "$TURNO_BACKUP_ENV_FILE"; require_vars TURNO_BACKUP_DIR
  newest=$(find "$TURNO_BACKUP_DIR" -maxdepth 1 -type f -name 'turnocontrol-db-*.dump.age' -mmin -120 -printf '%f\n' 2>/dev/null | sort | tail -1)
  [ -n "$newest" ] || die "no hay una copia de menos de 2 horas; ejecuta scripts/backup.sh antes de purgar"
  "$DEPLOY_DIR/scripts/verify-backup.sh" "$TURNO_BACKUP_DIR/$newest" >/dev/null || die "la copia más reciente no supera la verificación"
  log "copia previa verificada: $newest"
fi
mode=false; [ "$apply" = 1 ] && mode=true
result=$(db_psql -At -c "select turno_ops.purge_retention($days, $mode)")
echo "$result"
[ "$apply" = 1 ] && log "purga aplicada (solo recuentos arriba)" || log "simulación: nada borrado (usa --apply)"
